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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.helloworld;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;

/**
 * One transaction as this plugin recorded it.
 * <p>
 * The original returned {@code null} from every {@code PaymentPluginApi} method, which is enough to
 * demonstrate that a plugin loads but not that it works. This returns real results so the migration
 * can be verified end to end: a payment driven through Kill Bill's core reaches the plugin, and the
 * plugin's answer comes back.
 *
 * @param kbPaymentId     the payment Kill Bill is processing
 * @param kbTransactionId the transaction within it
 * @param transactionType what kind of transaction
 * @param amount          amount processed
 * @param currency        currency processed
 * @param effectiveDate   when the gateway processed it
 * @param gatewayReference the reference a real gateway would return
 */
public record HelloWorldTransaction(UUID kbPaymentId,
                                    UUID kbTransactionId,
                                    TransactionType transactionType,
                                    BigDecimal amount,
                                    Currency currency,
                                    DateTime effectiveDate,
                                    String gatewayReference) implements PaymentTransactionInfoPlugin {

    @Override
    public UUID getKbPaymentId() {
        return kbPaymentId;
    }

    @Override
    public UUID getKbTransactionPaymentId() {
        return kbTransactionId;
    }

    @Override
    public TransactionType getTransactionType() {
        return transactionType;
    }

    @Override
    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public Currency getCurrency() {
        return currency;
    }

    @Override
    public DateTime getCreatedDate() {
        return effectiveDate;
    }

    @Override
    public DateTime getEffectiveDate() {
        return effectiveDate;
    }

    @Override
    public PaymentPluginStatus getStatus() {
        return PaymentPluginStatus.PROCESSED;
    }

    @Override
    public String getGatewayError() {
        return null;
    }

    @Override
    public String getGatewayErrorCode() {
        return null;
    }

    @Override
    public String getFirstPaymentReferenceId() {
        return gatewayReference;
    }

    @Override
    public String getSecondPaymentReferenceId() {
        return null;
    }

    @Override
    public List<PluginProperty> getProperties() {
        return List.of();
    }
}
