/*
 * Copyright 2020-2026 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.platform.plugin.runtime;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.lpr.process.ProcessPluginClient;
import org.killbill.billing.lpr.process.ProcessPluginCallException;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.util.callcontext.CallContext;

/** Test/research fixture: one operation only. Never installed as a production payment gateway. */
final class ProcessAuthorizationPoc {
    private ProcessAuthorizationPoc() {}

    static PaymentPluginApi proxy(final ProcessPluginClient client) {
        return (PaymentPluginApi) Proxy.newProxyInstance(PaymentPluginApi.class.getClassLoader(), new Class<?>[] {PaymentPluginApi.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "ProcessAuthorizationPoc";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    if (!method.getName().equals("authorizePayment")) {
                        throw new PaymentPluginApiException("POC_UNSUPPORTED", "PoC supports authorization only");
                    }
                    return authorize(client, args);
                });
    }

    private static PaymentTransactionInfoPlugin authorize(final ProcessPluginClient client, final Object[] args) throws PaymentPluginApiException {
        final CallContext context = (CallContext) args[7];
        final List<String> request = List.of(args[0].toString(), args[1].toString(), args[2].toString(), args[3].toString(),
                ((BigDecimal) args[4]).toPlainString(), ((Currency) args[5]).name(), context.getTenantId().toString(), context.getUserToken().toString());
        try {
            final List<String> response = client.invoke("authorize.v1", request);
            if (response.size() != 10 || !response.subList(0, 8).equals(request)
                || !response.get(8).equals(Long.toString(client.pid())) || !response.get(9).equals("PROCESSED")) {
                throw new IOException("Invalid authorization reply");
            }
            return result(request, response.get(8));
        } catch (final ProcessPluginCallException e) {
            throw new PaymentPluginApiException("POC_RESULT_UNKNOWN", "Remote authorization failed; reconcile by transaction UUID, do not automatically retry");
        } catch (final IOException | IllegalStateException e) {
            throw new PaymentPluginApiException("POC_UNAVAILABLE", "Authorization unavailable; reconcile any previously dispatched transaction");
        }
    }

    private static PaymentTransactionInfoPlugin result(final List<String> data, final String workerPid) {
        final org.joda.time.DateTime now = org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC);
        return (PaymentTransactionInfoPlugin) Proxy.newProxyInstance(PaymentTransactionInfoPlugin.class.getClassLoader(),
                new Class<?>[] {PaymentTransactionInfoPlugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getKbPaymentId" -> UUID.fromString(data.get(1));
                    case "getKbTransactionPaymentId" -> UUID.fromString(data.get(2));
                    case "getTransactionType" -> TransactionType.AUTHORIZE;
                    case "getAmount" -> new BigDecimal(data.get(4));
                    case "getCurrency" -> Currency.valueOf(data.get(5));
                    case "getStatus" -> PaymentPluginStatus.PROCESSED;
                    case "getCreatedDate", "getEffectiveDate" -> now;
                    case "getFirstPaymentReferenceId" -> workerPid;
                    case "getSecondPaymentReferenceId" -> data.get(6);
                    case "getGatewayError", "getGatewayErrorCode" -> null;
                    case "getProperties" -> List.of();
                    case "toString" -> "ProcessAuthorizationResult";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
