package com.wizzdi.billing.payments.paypal.response;

import com.wizzdi.billing.payment.paypal.client.response.Product;

import java.util.Map;

public class PaypalProductsMapping {
    private final Map<String, Product> paypalProductMapping;

    public PaypalProductsMapping(Map<String, Product> paypalProductMapping) {
        this.paypalProductMapping = paypalProductMapping;
    }

    public Map<String, Product> getPaypalProductMapping() {
        return paypalProductMapping;
    }
}
