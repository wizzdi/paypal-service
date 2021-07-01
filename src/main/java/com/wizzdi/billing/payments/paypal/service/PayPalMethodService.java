package com.wizzdi.billing.payments.paypal.service;

import com.flexicore.billing.interfaces.PaymentMethodService;
import com.flexicore.billing.model.*;
import com.flexicore.billing.model.Currency;
import com.flexicore.billing.request.*;
import com.flexicore.billing.response.ActivateContractItemResponse;
import com.flexicore.billing.response.CreditCustomerResponse;
import com.flexicore.billing.service.ContractItemService;
import com.flexicore.billing.service.CurrencyService;
import com.flexicore.billing.service.InvoiceItemService;
import com.flexicore.billing.service.PaymentService;
import com.flexicore.security.SecurityContextBase;
import com.wizzdi.billing.payment.paypal.client.SubscriptionClient;
import com.wizzdi.billing.payment.paypal.client.request.SubscriptionCreate;
import com.wizzdi.billing.payment.paypal.client.response.*;
import com.wizzdi.billing.payments.paypal.response.PayPalContractItemActivationResponse;
import com.wizzdi.billing.payments.paypal.response.PaypalPlanMapping;
import com.wizzdi.flexicore.boot.base.interfaces.Plugin;
import com.wizzdi.flexicore.security.events.BasicCreated;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Extension
public class PayPalMethodService implements PaymentMethodService, Plugin {


    private static final Logger logger = LoggerFactory.getLogger(PayPalMethodService.class);
    @Autowired
    @Qualifier("paypalPaymentMethodType")
    private PaymentMethodType paypalPaymentMethodType;

    @Autowired
    private PaypalPlanMapping paypalPlanMapping;
    @Autowired
    private ContractItemService contractItemService;
    @Autowired
    private SubscriptionClient subscriptionClient;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CurrencyService currencyService;
    @Autowired
    private SecurityContextBase adminSecurityContext;
    @Autowired
    private InvoiceItemService invoiceItemService;

    @EventListener
    @Async
    public void onContractCreated(BasicCreated<Contract> contractCreated) {
        Contract contract = contractCreated.getBaseclass();
        if (contract.getAutomaticPaymentMethod() != null && !contract.getAutomaticPaymentMethod().getPaymentMethodType().getId().equals(paypalPaymentMethodType.getId())) {
            List<ContractItem> contractItems = contractItemService.listAllContractItems(null, new ContractItemFiltering().setContracts(Collections.singletonList(contract)));
            for (ContractItem contractItem : contractItems) {
                BusinessService businessService = contractItem.getBusinessService();
                String planExternalId = contractItem.getPriceListToService().getId();
                Plan plan = paypalPlanMapping.getPlanMapping().get(planExternalId);
                if (plan == null) {
                    logger.error("Could not find paypal plan with id " + planExternalId + ", no such plan in paypal");
                }
                SubscriptionCreate subscriptionCreate = new SubscriptionCreate();
                subscriptionCreate.setPlanId(plan.getId());
                subscriptionCreate.setStartTime(OffsetDateTime.now());
                Subscriber subscriber = new Subscriber();
                //TODO:subscriber
                subscriptionCreate.setSubscriber(subscriber);
                Subscription subscription = subscriptionClient.createSubscription(contract.getId(), subscriptionCreate).getBody();
                contractItem.setExternalId(subscription.getId());
            }

        }
    }

    @Override
    public ActivateContractItemResponse activateContractItem(ActivateContractItemRequest activateContractItemRequest) {
        String subscriptionId = activateContractItemRequest.getContractItem().getExternalId();
        Subscription subscription = subscriptionClient.getSubscription(subscriptionId).getBody();
        String approveLink = getApproveLink(subscription);
        return new PayPalContractItemActivationResponse().setApproveLink(approveLink);

    }

    private String getApproveLink(Subscription subscription) {
        for (Link link : subscription.getLinks()) {
            if (link.getRel().equals("approve")) {
                return link.getHref();
            }
        }
        return null;
    }

    @Override
    public CreditCustomerResponse creditCustomer(CreditCustomerRequest payRequest) {
        List<Payment> importedPayments = importPayments(payRequest);
        Map<String, List<InvoiceItem>> invoiceItemsForContractItem = payRequest.getInvoiceItem().stream().collect(Collectors.groupingBy(f -> f.getContractItem().getId()));
        Map<String, List<Payment>> paymentsForContractItem = importedPayments.stream().collect(Collectors.groupingBy(f -> f.getContractItem().getId()));
        List<InvoiceItem> matchFound = new ArrayList<>();
        List<InvoiceItem> matchNotFound = new ArrayList<>();
        for (Map.Entry<String, List<InvoiceItem>> invoiceEntry : invoiceItemsForContractItem.entrySet()) {
            String contractItemId = invoiceEntry.getKey();
            List<Payment> payments = paymentsForContractItem.getOrDefault(contractItemId, new ArrayList<>());
            for (InvoiceItem invoiceItem : invoiceEntry.getValue()) {
                Comparator<Payment> paymentComparator = getPaymentComparator(invoiceItem);
                Optional<Payment> matchingPayment = payments.stream().filter(f -> canMatch(f, invoiceItem)).min(paymentComparator);
                if (matchingPayment.isPresent()) {
                    Payment payment = matchingPayment.get();
                    matchFound.add(invoiceItemService.updateInvoiceItem(new InvoiceItemUpdate().setInvoiceItem(invoiceItem).setPayment(payment), null));
                } else {
                    matchNotFound.add(invoiceItem);
                }


            }
        }
        return new CreditCustomerResponse()
                .setMatchedInvoiceItems(matchFound)
                .setUnmatchedInvoiceItems(matchNotFound);
    }

    private Comparator<Payment> getPaymentComparator(InvoiceItem invoiceItem) {
        Comparator<Payment> paymentComparator = Comparator.comparing(f -> Math.abs(f.getPrice() - invoiceItem.getContractItem().getPrice()));
        paymentComparator=paymentComparator.thenComparing(f->Math.abs(f.getDatePaid().toInstant().toEpochMilli()- invoiceItem.getInvoice().getInvoiceDate().toInstant().toEpochMilli()));
        return paymentComparator;
    }

    private boolean canMatch(Payment payment, InvoiceItem invoiceItem) {
        return payment.getCurrency().getId().equals(invoiceItem.getContractItem().getCurrency().getId()) &&
                payment.getPrice()>=invoiceItem.getContractItem().getPrice();
    }

    private List<Payment> importPayments(CreditCustomerRequest payRequest) {
        Map<String, ContractItem> contractItems = payRequest.getInvoiceItem().stream().map(f -> f.getContractItem()).collect(Collectors.toMap(f -> f.getId(), f -> f, (a, b) -> a));
        Map<String, Currency> currencies = currencyService.listAllCurrencies(null, new CurrencyFiltering()).stream().collect(Collectors.toMap(f -> f.getName(), f -> f));
        Map<String, Payment> allPayments = new HashMap<>();
        for (ContractItem contractItem : contractItems.values()) {
            String subscriptionId = contractItem.getExternalId();
            SubscriptionTransactionResponse subscriptionTransactionResponse = subscriptionClient.getSubscriptionTransactions(subscriptionId, contractItem.getValidFrom().toInstant(), contractItem.getValidTo().toInstant()).getBody();
            Map<String, Payment> paymentMap = subscriptionTransactionResponse.getTransactions().isEmpty() ? new HashMap<>() : paymentService.listAllPayments(null, new PaymentFiltering().setPaymentReferences(subscriptionTransactionResponse.getTransactions().stream().map(f -> f.getId()).collect(Collectors.toSet()))).stream().collect(Collectors.toMap(f -> f.getId(), f -> f, (a, b) -> a));
            for (Transaction transaction : subscriptionTransactionResponse.getTransactions()) {
                Payment payment = paymentMap.get(transaction.getId());
                if (payment == null) {
                    Amount amountWithBreakDown = transaction.getAmountWithBreakDown().getNetAmount();
                    PaymentCreate paymentCreate = new PaymentCreate()
                            .setPaymentReference(transaction.getId())
                            .setCurrency(currencies.get(amountWithBreakDown.getCurrencyCode()))
                            .setPrice((long) (Double.parseDouble(amountWithBreakDown.getValue()) * 1000))
                            .setContractItem(contractItem)
                            .setDatePaid(transaction.getTime());
                    payment = paymentService.createPayment(paymentCreate, adminSecurityContext);
                    paymentMap.put(payment.getPaymentReference(), payment);
                }
            }
            allPayments.putAll(paymentMap);

        }
        return new ArrayList<>(allPayments.values());
    }


    @Override
    public boolean isType(PaymentMethodType paymentMethodType) {
        return paymentMethodType.getId().equals(paypalPaymentMethodType.getId());
    }


}
