"""Exercise production REST resources; all payments use the local hello-world plugin."""

import base64
from decimal import Decimal
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Api:
    def __init__(self, port):
        self.base = f"http://127.0.0.1:{port}"
        self.headers = {"Authorization": "Basic " + base64.b64encode(b"admin:password").decode(),
                        "X-Killbill-CreatedBy": "release-validation"}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path, body=None, method="GET", expected=(200,), content_type="application/json"):
        payload = body.encode() if isinstance(body, str) else json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=payload, method=method,
                                         headers={**self.headers, "Content-Type": content_type})
        try:
            with self.opener.open(request, timeout=15) as response:
                data = response.read()
                if response.status not in expected:
                    raise RuntimeError(f"{method} {path}: unexpected HTTP {response.status}")
                value = json.loads(data, parse_float=Decimal) if data else None
                return value, response.headers
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")[:1500]
            raise RuntimeError(f"{method} {path}: HTTP {error.code}: {detail}") from error

    def get(self, path):
        return self.request(path)[0]

    def create(self, path, body):
        _, headers = self.request(path, body, "POST", (201,))
        # Use only the relative resource path; never follow an arbitrary redirect.
        return urllib.parse.urlsplit(headers["Location"]).path


def await_value(description, action, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        result = action()
        if predicate(result):
            return result
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {description}; last result: {str(result)[:1000]}")
        time.sleep(0.25)


def check_plugin(api):
    plugins = api.get("/1.0/kb/pluginsInfo")
    candidates = [p for p in plugins if p.get("pluginKey") == "hello-world"]
    if not candidates or not any(p.get("state") == "RUNNING" for p in candidates):
        raise RuntimeError(f"hello-world plugin is not running: {plugins}")


def business_flow(api, call_completion=True):
    key, secret = uuid.uuid4().hex, uuid.uuid4().hex
    api.create("/1.0/kb/tenants?useGlobalDefault=true", {"apiKey": key, "apiSecret": secret})
    api.headers.update({"X-Killbill-ApiKey": key, "X-Killbill-ApiSecret": secret})
    check_plugin(api)
    account_path = api.create("/1.0/kb/accounts", {
        "name": "Release acceptance", "externalKey": uuid.uuid4().hex,
        "currency": "USD", "timeZone": "UTC"})
    account = api.get(account_path)
    account_id = account["accountId"]
    # Leave payment method non-default until invoice creation so payment is explicit.
    method_path = api.create(f"/1.0/kb/accounts/{account_id}/paymentMethods", {
        "pluginName": "hello-world-plugin", "pluginInfo": {"properties": []}})
    method_id = method_path.rsplit("/", 1)[1]
    subscription_path = api.create("/1.0/kb/subscriptions?callCompletion=" + str(call_completion).lower(), {
        "accountId": account_id, "planName": "standard-monthly", "phaseType": "EVERGREEN"})
    subscription = api.get(subscription_path)
    if subscription["state"] != "ACTIVE":
        raise RuntimeError(f"Subscription is not active: {subscription}")
    invoices = await_value("a billable subscription invoice",
                           lambda: api.get(f"/1.0/kb/accounts/{account_id}/invoices?includeInvoiceComponents=true"),
                           lambda values: any(Decimal(str(i["balance"])) > 0 for i in values))
    invoice = next(i for i in invoices if Decimal(str(i["balance"])) > 0)
    invoice_id = invoice["invoiceId"]
    payment_path = api.create(f"/1.0/kb/invoices/{invoice_id}/payments", {
        "accountId": account_id, "paymentMethodId": method_id,
        "purchasedAmount": str(invoice["balance"]), "paymentExternalKey": uuid.uuid4().hex})
    payment_id = payment_path.rstrip("/").rsplit("/", 1)[1]
    state = {"accountId": account_id, "subscriptionPath": subscription_path,
             "invoiceId": invoice_id, "paymentId": payment_id, "amount": str(invoice["amount"])}
    verify_business(api, state)
    return state


def verify_business(api, state):
    account = api.get(f'/1.0/kb/accounts/{state["accountId"]}')
    subscription = api.get(state["subscriptionPath"])
    invoice = await_value("settled invoice", lambda: api.get(f'/1.0/kb/invoices/{state["invoiceId"]}'),
                          lambda value: Decimal(str(value["balance"])) == 0)
    payment = api.get(f'/1.0/kb/payments/{state["paymentId"]}')
    if account["accountId"] != state["accountId"] or subscription["state"] != "ACTIVE":
        raise RuntimeError("Account or subscription did not survive restart")
    if Decimal(str(invoice["amount"])) != Decimal(state["amount"]) or invoice["status"] != "COMMITTED":
        raise RuntimeError("Invoice amount/status changed")
    successful = [t for t in payment["transactions"] if t["status"] == "SUCCESS" and t["transactionType"] == "PURCHASE"]
    if len(successful) != 1 or Decimal(str(successful[0]["amount"])) != Decimal(state["amount"]):
        raise RuntimeError(f"Expected one successful purchase: {payment}")
