package com.wizzdi.billing.payments.paypal.response;

import com.wizzdi.billing.payment.paypal.client.response.Plan;

import java.util.Map;

public class PaypalPlanMapping {

    private final Map<String, Plan> planMapping;

    public PaypalPlanMapping(Map<String, Plan> planMapping) {
        this.planMapping = planMapping;
    }

    public Map<String, Plan> getPlanMapping() {
        return planMapping;
    }
}
