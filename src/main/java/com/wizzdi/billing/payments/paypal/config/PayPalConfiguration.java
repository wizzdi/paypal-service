package com.wizzdi.billing.payments.paypal.config;

import com.flexicore.billing.model.*;
import com.flexicore.billing.request.BusinessServiceFiltering;
import com.flexicore.billing.request.PaymentMethodTypeCreate;
import com.flexicore.billing.request.PaymentMethodTypeFiltering;
import com.flexicore.billing.request.PriceListToServiceFiltering;
import com.flexicore.billing.service.BusinessServiceService;
import com.flexicore.billing.service.PaymentMethodTypeService;
import com.flexicore.billing.service.PriceListToServiceService;
import com.flexicore.security.SecurityContextBase;
import com.wizzdi.billing.payment.paypal.client.SubscriptionClient;
import com.wizzdi.billing.payment.paypal.client.request.PlanCreate;
import com.wizzdi.billing.payment.paypal.client.request.ProductCreate;
import com.wizzdi.billing.payment.paypal.client.request.ProductType;
import com.wizzdi.billing.payment.paypal.client.response.*;
import com.wizzdi.billing.payments.paypal.response.PaypalPlanMapping;
import com.wizzdi.billing.payments.paypal.response.PaypalProductsMapping;
import com.wizzdi.billing.payments.paypal.service.PayPalMethodService;
import com.wizzdi.flexicore.boot.base.interfaces.Plugin;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.wizzdi.billing.payment.paypal.client.response.InternalUnit.*;

@Configuration
@Extension
public class PayPalConfiguration implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(PayPalConfiguration.class);

    @Value("${flexicore.billing.payments.paypal.clientId}")
    private String clientId;
    @Value("${flexicore.billing.payments.paypal.secret}")
    private String secret;
    @Value("${flexicore.billing.payments.paypal.live}")
    private boolean live;
    @Autowired
    private SecurityContextBase adminSecurityContext;

    @Autowired
    private PaymentMethodTypeService paymentMethodTypeService;

    @Autowired
    private SubscriptionClient subscriptionClient;
    @Autowired
    private BusinessServiceService businessServiceService;
    @Autowired
    private PriceListToServiceService priceListToServiceService;


    @Bean
    @Qualifier("paypalPaymentMethodType")
    public PaymentMethodType paypalPaymentMethodType() {
        return paymentMethodTypeService.listAllPaymentMethodTypes(adminSecurityContext, new PaymentMethodTypeFiltering().setCanonicalClassNames(Collections.singleton(PayPalMethodService.class.getCanonicalName())))
                .stream()
                .findFirst()
                .orElse(createPaypalMethodType());
    }

    private PaymentMethodType createPaypalMethodType() {
        PaymentMethodTypeCreate paymentMethodTypeCreate = new PaymentMethodTypeCreate()
                .setCanonicalClassName(PayPalMethodService.class.getCanonicalName())
                .setName("PayPal")
                .setDescription("Pay with PayPal");

        return paymentMethodTypeService.createPaymentMethodType(paymentMethodTypeCreate, adminSecurityContext);
    }


    @Bean
    public SubscriptionClient subscriptionClient() {
        return new SubscriptionClient(SubscriptionClient.SANDBOX_URL, clientId, secret);
    }

    @Bean
    public PaypalProductsMapping paypalProductsMapping() {
        List<BusinessService> businessServices = businessServiceService.listAllBusinessServices(null, new BusinessServiceFiltering());
        List<Product> products;
        Map<String, Product> paypalProducts = new HashMap<>();
        for (int currentPage = 1; (products = subscriptionClient.getProducts(50, currentPage, false).getBody().getProducts()).isEmpty(); currentPage++) {
            paypalProducts.putAll(products.stream().collect(Collectors.toMap(f -> f.getId(), f -> f)));
        }
        for (BusinessService businessService : businessServices) {
            Product product = paypalProducts.get(businessService.getId());
            if (product == null) {
                ProductCreate productCreate = new ProductCreate();
                productCreate.setType(ProductType.SERVICE);
                productCreate.setName(businessService.getName());
                productCreate.setId(businessService.getId());
                productCreate.setDescription(businessService.getDescription());
                product = subscriptionClient.createProduct(businessService.getId(), productCreate).getBody();
                paypalProducts.put(product.getId(), product);
            }

        }

        return new PaypalProductsMapping(paypalProducts);


    }

    @Bean
    public PaypalPlanMapping paypalPlanMapping(PaypalProductsMapping paypalProductsMapping) {
        List<PriceListToService> priceListToServices = priceListToServiceService.listAllPriceListToServices(null, new PriceListToServiceFiltering());
        List<Plan> plans;
        Map<String, Plan> paypalPlans = new HashMap<>();
        for (int currentPage = 1; (plans = subscriptionClient.getPlans(50, currentPage, false, null, null).getBody().getPlans()).isEmpty(); currentPage++) {
            Map<String, Plan> collect = plans.stream().collect(Collectors.toMap(f -> (String) f.getAdditionalProperties().get("externalId"), f -> f));
            paypalPlans.putAll(collect);
        }

        for (PriceListToService priceListToService : priceListToServices) {
            Plan plan = paypalPlans.get(priceListToService.getId());
            if (plan == null) {
                String productId = priceListToService.getBusinessService().getId();
                Product product = paypalProductsMapping.getPaypalProductMapping().get(productId);
                if (product == null) {
                    logger.error("Paypal Product " + productId + " not found , cannot create paypal plan for " + priceListToService.getId());
                    continue;
                }
                PlanCreate planCreate = new PlanCreate();
                planCreate.setStatus(PlanStatus.ACTIVE);
                planCreate.setProductId(product.getId());
                PaymentPreferences paymentPreferences = new PaymentPreferences();
                paymentPreferences.setAutoBillOutstanding(true);
                paymentPreferences.setSetupFeeFailureAction(SetupFeeFailureAction.CANCEL);
                planCreate.setPaymentPreferences(paymentPreferences);
                BillingCycle billingCycle = new BillingCycle();
                billingCycle.setSequence(1);
                billingCycle.setTenureType(TenureType.REGULAR);
                Frequency frequency = new Frequency();
                if (priceListToService.getPaymentType() == PaymentType.RECURRING) {
                    billingCycle.setTotalCycles(priceListToService.getTotalCycles());
                    frequency.setIntervalUnit(getIntervalUnit(priceListToService.getBillingCycleGranularity()));
                    frequency.setIntervalCount(priceListToService.getBillingCycleInterval());
                } else {
                    billingCycle.setTotalCycles(1);
                    frequency.setIntervalUnit(DAY);
                    frequency.setIntervalCount(1);
                }


                billingCycle.setFrequency(frequency);
                PricingScheme pricingScheme = new PricingScheme();
                pricingScheme.setVersion(1);
                FixedPrice fixedPrice = new FixedPrice();
                fixedPrice.setCurrencyCode(priceListToService.getCurrency().getName());
                fixedPrice.setValue(priceListToService.getPrice()+"");
                pricingScheme.setFixedPrice(fixedPrice);
                billingCycle.setPricingScheme(pricingScheme);
                planCreate.setBillingCycles(Collections.singletonList(billingCycle));

                plan=subscriptionClient.createPlan(priceListToService.getId(),planCreate).getBody();
                paypalPlans.put(priceListToService.getId(),plan);

            }
        }
        return new PaypalPlanMapping(paypalPlans);

    }

    private InternalUnit getIntervalUnit(BillingCycleGranularity billingCycleGranularity) {
        switch (billingCycleGranularity) {
            case DAYS:
                return DAY;
            case WEEKS:
                return WEEK;
            case YEARS:
                return YEAR;
            case MONTHS:
                return MONTH;
        }
        return null;

    }
}
