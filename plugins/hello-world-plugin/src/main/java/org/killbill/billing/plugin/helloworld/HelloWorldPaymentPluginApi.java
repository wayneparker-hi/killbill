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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.entity.Pagination;

/**
 * A payment gateway that always succeeds, keeping its transactions in memory.
 * <p>
 * The original returned {@code null} from all eighteen methods. That is enough to show a plugin
 * being loaded, but not that a payment actually reaches it and comes back -- and "the plugin loads"
 * was never the part of the migration in doubt. Recording transactions makes the round trip
 * observable, which is what the migration test asserts on.
 * <p>
 * This file is the interesting one for anyone porting a plugin, because <b>it did not have to
 * change</b>. It implements {@code PaymentPluginApi} from {@code killbill-plugin-api-payment}, the
 * same interface as before; the plugin SPI never depended on OSGi. Only the Activator did.
 */
public class HelloWorldPaymentPluginApi implements PaymentPluginApi {

    private final String greeting;
    private final Map<UUID, List<PaymentTransactionInfoPlugin>> transactionsByPayment = new ConcurrentHashMap<>();
    private final AtomicInteger transactionCount = new AtomicInteger();

    /**
     * @param greeting from the plugin's configuration, so the migration test can prove that
     *                 {@code plugin.yaml} config reaches the plugin
     */
    public HelloWorldPaymentPluginApi(final String greeting) {
        this.greeting = greeting;
    }

    /**
     * @return how many transactions this instance has processed; used by the plugin's stop path and
     *         by tests to show a plugin holds its own state across calls
     */
    public int transactionCount() {
        return transactionCount.get();
    }

    /**
     * @return the greeting this instance was configured with
     */
    public String greeting() {
        return greeting;
    }

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                         final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                         final BigDecimal amount, final Currency currency,
                                                         final Iterable<PluginProperty> properties,
                                                         final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.AUTHORIZE, amount, currency);
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                       final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                       final BigDecimal amount, final Currency currency,
                                                       final Iterable<PluginProperty> properties,
                                                       final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.CAPTURE, amount, currency);
    }

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                        final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                        final BigDecimal amount, final Currency currency,
                                                        final Iterable<PluginProperty> properties,
                                                        final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.PURCHASE, amount, currency);
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                    final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                    final Iterable<PluginProperty> properties,
                                                    final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.VOID, null, null);
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                      final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                      final BigDecimal amount, final Currency currency,
                                                      final Iterable<PluginProperty> properties,
                                                      final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.CREDIT, amount, currency);
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                      final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                      final BigDecimal amount, final Currency currency,
                                                      final Iterable<PluginProperty> properties,
                                                      final CallContext context) throws PaymentPluginApiException {
        return record(kbPaymentId, kbTransactionId, TransactionType.REFUND, amount, currency);
    }

    @Override
    public List<PaymentTransactionInfoPlugin> getPaymentInfo(final UUID kbAccountId, final UUID kbPaymentId,
                                                             final Iterable<PluginProperty> properties,
                                                             final TenantContext context) {
        return List.copyOf(transactionsByPayment.getOrDefault(kbPaymentId, List.of()));
    }

    @Override
    public Pagination<PaymentTransactionInfoPlugin> searchPayments(final String searchKey, final Long offset,
                                                                   final Long limit,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final TenantContext context) {
        return emptyPage();
    }

    @Override
    public void addPaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                 final PaymentMethodPlugin paymentMethodProps, final boolean setDefault,
                                 final Iterable<PluginProperty> properties, final CallContext context) {
        // A real gateway would tokenize the instrument here.
    }

    @Override
    public void deletePaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                    final Iterable<PluginProperty> properties, final CallContext context) {
    }

    @Override
    public PaymentMethodPlugin getPaymentMethodDetail(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                                      final Iterable<PluginProperty> properties,
                                                      final TenantContext context) {
        return null;
    }

    @Override
    public void setDefaultPaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                        final Iterable<PluginProperty> properties, final CallContext context) {
    }

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(final UUID kbAccountId, final boolean refreshFromGateway,
                                                           final Iterable<PluginProperty> properties,
                                                           final CallContext context) {
        return List.of();
    }

    @Override
    public Pagination<PaymentMethodPlugin> searchPaymentMethods(final String searchKey, final Long offset,
                                                                final Long limit,
                                                                final Iterable<PluginProperty> properties,
                                                                final TenantContext context) {
        return emptyPage();
    }

    @Override
    public void resetPaymentMethods(final UUID kbAccountId, final List<PaymentMethodInfoPlugin> paymentMethods,
                                    final Iterable<PluginProperty> properties, final CallContext context) {
    }

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(final UUID kbAccountId,
                                                               final Iterable<PluginProperty> customFields,
                                                               final Iterable<PluginProperty> properties,
                                                               final CallContext context) {
        return null;
    }

    @Override
    public GatewayNotification processNotification(final String notification,
                                                   final Iterable<PluginProperty> properties,
                                                   final CallContext context) {
        return null;
    }

    private PaymentTransactionInfoPlugin record(final UUID kbPaymentId, final UUID kbTransactionId,
                                                final TransactionType transactionType,
                                                final BigDecimal amount, final Currency currency) {
        final HelloWorldTransaction transaction = new HelloWorldTransaction(
                kbPaymentId,
                kbTransactionId,
                transactionType,
                amount,
                currency,
                DateTime.now(DateTimeZone.UTC),
                greeting + '-' + transactionCount.incrementAndGet());

        transactionsByPayment.compute(kbPaymentId, (id, existing) -> {
            final List<PaymentTransactionInfoPlugin> transactions =
                    existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            transactions.add(transaction);
            return List.copyOf(transactions);
        });
        return transaction;
    }

    private <T> Pagination<T> emptyPage() {
        return new Pagination<T>() {
            @Override
            public Long getCurrentOffset() {
                return 0L;
            }

            @Override
            public Long getNextOffset() {
                return null;
            }

            @Override
            public Long getMaxNbRecords() {
                return 0L;
            }

            @Override
            public Long getTotalNbRecords() {
                return 0L;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public void close() throws IOException {
            }
        };
    }
}
