package com.wizzdi.billing.payments.paypal.response;

import com.flexicore.billing.response.ActivateContractItemResponse;

public class PayPalContractItemActivationResponse extends ActivateContractItemResponse {

    private String approveLink;

    public String getApproveLink() {
        return approveLink;
    }

    public <T extends PayPalContractItemActivationResponse> T setApproveLink(String approveLink) {
        this.approveLink = approveLink;
        return (T) this;
    }
}
