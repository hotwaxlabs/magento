/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.magento;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import magento.AssociativeEntity;
import magento.ComplexFilter;
import magento.ComplexFilterArray;
import magento.DirectoryRegionEntity;
import magento.Filters;
import magento.SalesOrderAddressEntity;
import magento.SalesOrderEntity;
import magento.SalesOrderItemEntity;
import magento.SalesOrderItemEntityArray;
import magento.SalesOrderListEntity;
import magento.SalesOrderPaymentEntity;
import magento.StoreEntity;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.ObjectType;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityFunction;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.order.order.OrderChangeHelper;
import org.ofbiz.order.shoppingcart.CheckOutHelper;
import org.ofbiz.order.shoppingcart.ItemNotFoundException;
import org.ofbiz.order.shoppingcart.ShoppingCart;
import org.ofbiz.order.shoppingcart.ShoppingCartItem;
import org.ofbiz.product.store.ProductStoreWorker;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;


public class MagentoHelper {
    public static final String SALES_CHANNEL = "MAGENTO_SALE_CHANNEL";
    public static final String ORDER_TYPE = "SALES_ORDER";

    public static final int SHIPPING_ADDRESS = 10;
    public static final int BILLING_ADDRESS = 50;

    public static final String module = MagentoHelper.class.getName();
    @SuppressWarnings("unchecked")
    public static String createOrder(SalesOrderEntity orderInformation, Locale locale, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        GenericValue magentoConfiguration = null;
        String productStoreId = null;
        String websiteId = null;
        String prodCatalogId = null;

        // get the magento order number
        String externalId = orderInformation.getIncrementId();

        // check and make sure if order with externalId already exist
        List<GenericValue> existingOrder = delegator.findList("OrderHeader", EntityCondition.makeCondition("externalId", externalId), null, null, null, false);
        if (UtilValidate.isNotEmpty(existingOrder) && existingOrder.size() > 0) {
            //throw new GeneralException("Ofbiz order #" + externalId + " already exists.");
            Debug.logWarning("Ofbiz order #" + externalId + " already exists.", module);
            return "Ofbiz order #" + externalId + " already exists.";
        }
        GenericValue system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system"));
        MagentoClient magentoClient = new MagentoClient(dispatcher, delegator);
        String magentoStoreId = orderInformation.getStoreId();
        if (UtilValidate.isNotEmpty(magentoStoreId)) {
            StoreEntity storeInfo = magentoClient.getStoreInfo(magentoStoreId);
            if (UtilValidate.isNotEmpty(storeInfo.getGroupId())) {
                String magentoStoreGroupId = String.valueOf(storeInfo.getGroupId());
                GenericValue magentoProductStore = EntityUtil.getFirst(delegator.findList("MagentoProductStore", EntityCondition.makeCondition("magentoStoreGroupId", magentoStoreGroupId), null, null, null, false));
                if (UtilValidate.isNotEmpty(magentoProductStore)) {
                    productStoreId = magentoProductStore.getString("productStoreId");
                }
            }
        }
        if (UtilValidate.isEmpty(productStoreId)) {
        magentoConfiguration = EntityUtil.getFirst(delegator.findList("MagentoConfiguration", EntityCondition.makeCondition("enumId", EntityOperator.EQUALS, "MAGENTO_SALE_CHANNEL"), null, null, null, false));
        if (UtilValidate.isNotEmpty(magentoConfiguration)) {
            productStoreId = magentoConfiguration.getString("productStoreId");
        }
        }
        String currencyUom = orderInformation.getOrderCurrencyCode();

     // Initialize the shopping cart
        ShoppingCart cart = new ShoppingCart(delegator, productStoreId, websiteId, locale, currencyUom);
        cart.setUserLogin(system, dispatcher);
        cart.setOrderType(ORDER_TYPE);
        cart.setChannelType(SALES_CHANNEL);
        //cart.setOrderDate(UtilDateTime.toTimestamp(info.getTimestamp().()));
        cart.setExternalId(externalId);

        Debug.logInfo("Created shopping cart for Magento order: ", module);
        Debug.logInfo("-- WebSite : " + websiteId, module);
        Debug.logInfo("-- Product Store : " + productStoreId, module);
        Debug.logInfo("-- Locale : " + locale.toString(), module);
        Debug.logInfo("-- Magento Order # : " + externalId, module);

        // set the customer information
        SalesOrderAddressEntity shippingAddress = orderInformation.getShippingAddress();
        SalesOrderAddressEntity billingAddress = orderInformation.getBillingAddress();
        
        List<DirectoryRegionEntity> shippingDirectoryRegionList = magentoClient.getDirectoryRegionList(shippingAddress.getCountryId());
        for (DirectoryRegionEntity region : shippingDirectoryRegionList) {
            if ((region.getRegionId()).equals(shippingAddress.getRegionId())) {
                shippingAddress.setRegion(region.getCode());
                break;
            }
        }

        List<DirectoryRegionEntity> billingDirectoryRegionList = magentoClient.getDirectoryRegionList(shippingAddress.getCountryId());
        for (DirectoryRegionEntity region : billingDirectoryRegionList) {
            if ((region.getRegionId()).equals(shippingAddress.getRegionId())) {
                billingAddress.setRegion(region.getCode());
                break;
            }
        }
        String[] partyInfo = setPartyInfo(orderInformation.getCustomerId(), orderInformation.getCustomerEmail(), shippingAddress, billingAddress, delegator, dispatcher);
        if (partyInfo == null || partyInfo.length != 3) {
            throw new GeneralException("Unable to parse/create party information, invalid number of parameters returned");
        }
        cart.setOrderPartyId(partyInfo[0]);
        cart.setPlacingCustomerPartyId(partyInfo[0]);
        cart.setShippingContactMechId(0, partyInfo[1]);
        // contact info
        if (UtilValidate.isNotEmpty(shippingAddress)) {
            if (UtilValidate.isNotEmpty(orderInformation.getCustomerEmail())) {
                String shippingEmail = orderInformation.getCustomerEmail();
                setContactInfo(cart, "PRIMARY_EMAIL", shippingEmail, delegator, dispatcher);
            }
            if (UtilValidate.isNotEmpty(shippingAddress.getTelephone())) {
                String shippingPhone = shippingAddress.getTelephone();
                setContactInfo(cart, "PHONE_SHIPPING", shippingPhone, delegator, dispatcher);
            }
        }
        if (UtilValidate.isNotEmpty(billingAddress)) {
            if(UtilValidate.isNotEmpty(orderInformation.getCustomerEmail())) {
                String billingEmail = orderInformation.getCustomerEmail();
                setContactInfo(cart, "BILLING_EMAIL", billingEmail, delegator, dispatcher);
            }
            if (UtilValidate.isNotEmpty(billingAddress.getTelephone())) {
                String billingPhone = billingAddress.getTelephone();
                setContactInfo(cart, "PHONE_BILLING", billingPhone, delegator, dispatcher);
            }
        }
        // set the order items
        SalesOrderItemEntityArray salesOrderItemEntityArray = orderInformation.getItems();
        List<SalesOrderItemEntity> orderItems = salesOrderItemEntityArray.getComplexObjectArray();
        HashMap<String, Object> productData = null;
        BigDecimal price = null;

        HashMap<String, SalesOrderItemEntity> items = new HashMap<String, SalesOrderItemEntity>();
        for (SalesOrderItemEntity orderItem : orderItems) {
            if ("configurable".equals(orderItem.getProductType())) {
                items.put(orderItem.getSku(), orderItem);
            }
            
        }

        for (SalesOrderItemEntity item : orderItems) {
            try {
                productData = new HashMap<String, Object>();
                productData.put("productTypeId", "FINISHED_GOOD");
                productData.put("internalName", item.getName());
                productData.put("productName", item.getName());
                productData.put("userLogin", system);
                String idValue = item.getProductId();

                // Handling Magento's Product Id.
                EntityCondition cond = EntityCondition.makeCondition(
                        EntityCondition.makeCondition("idValue", idValue),
                        EntityCondition.makeCondition("goodIdentificationTypeId", "MAGENTO_ID")
                        );
                GenericValue goodIdentification = EntityUtil.getFirst(delegator.findList("GoodIdentification", cond, null, null, null, false));
                if ("bundle".equals(item.getProductType())) {
                    continue;
                } else if ("configurable".equals(item.getProductType())) {
                    price =  new BigDecimal(item.getPrice());
                    continue;
                } else if ("simple".equals(item.getProductType()) && (UtilValidate.isEmpty(items) || UtilValidate.isEmpty(items.get(item.getSku())))) {
                    price =  new BigDecimal(item.getPrice());
                } else if ("grouped".equals(item.getProductType())) {
                    price =  new BigDecimal(item.getPrice());
                }
                if (UtilValidate.isNotEmpty(goodIdentification) && "configurable".equals(item.getProductType())) {
                    continue;
                } else if (UtilValidate.isNotEmpty(goodIdentification)) {
                    productData.put("productId", goodIdentification.get("productId"));
                    productData.put("price", price);
                    productData.put("quantity", item.getQtyOrdered());
                } else {
                    Debug.logError("Ordered Magento product is not available in OFBiz", module);
                    return null;
                }
                if ("simple".equals(item.getProductType())) {
                    SalesOrderItemEntity orderItem = items.get(item.getSku());
                    if (UtilValidate.isNotEmpty(orderItem)) {
                        productData.put("orderItemId", orderItem.getItemId());
                    } else {
                        productData.put("orderItemId", item.getItemId());
                    }
                }

                addItem(cart, productData, prodCatalogId, 0, delegator, dispatcher);
            } catch (ItemNotFoundException e) {
                Debug.logError("Unable to obtain GoodIdentification entity value of the Magento id for product [" + orderInformation.getParentId() + "]: " + e.getMessage(), module);
            }
        }
        // handle the adjustments
        HashMap<String,String> adjustment = new HashMap<String, String>();
        adjustment.put("orderTaxAmount", orderInformation.getTaxAmount());
        adjustment.put("orderDiscountAmount", orderInformation.getDiscountAmount());
        adjustment.put("orderShippingAmount", orderInformation.getShippingAmount());
        if (UtilValidate.isNotEmpty(adjustment)) {
            addAdjustments(cart, adjustment, delegator);
            // ship group info
            if (UtilValidate.isNotEmpty(orderInformation.getShippingMethod())) {
                String ShippingMethod = orderInformation.getShippingMethod();
                String carrierPartyId = ShippingMethod.substring(0, ShippingMethod.indexOf("_")).toUpperCase();
                if("FLATRATE".equalsIgnoreCase(carrierPartyId)) {
                    carrierPartyId = "_NA_";
                }

                String shipmentMethodTypeId = null;
                String carrierServiceCode = ShippingMethod.replace("_", "").toUpperCase();
                EntityCondition condition = EntityCondition.makeCondition(
                        EntityCondition.makeCondition("roleTypeId", "CARRIER"),
                        EntityCondition.makeCondition("partyId", carrierPartyId),
                        EntityCondition.makeCondition(EntityFunction.UPPER_FIELD("carrierServiceCode"), EntityOperator.EQUALS ,carrierServiceCode)
                        );
                GenericValue carrierShipmentMethod = EntityUtil.getFirst(delegator.findList("CarrierShipmentMethod", condition, UtilMisc.toSet("shipmentMethodTypeId"), null, null, false));
                if (UtilValidate.isNotEmpty(carrierShipmentMethod)) {
                    shipmentMethodTypeId = carrierShipmentMethod.getString("shipmentMethodTypeId");
                } else if (UtilValidate.isEmpty(shipmentMethodTypeId)) {
                    String magShipmentMethodTypeId = ShippingMethod.substring(ShippingMethod.indexOf("_") + 1);
                    //shipmentMethodName = "Ground";
                    Debug.logInfo("Magento ShipmentMethodTypeId :"+magShipmentMethodTypeId, module);
                    shipmentMethodTypeId = EntityUtilProperties.getPropertyValue("Magento", magShipmentMethodTypeId, delegator);
                    if (UtilValidate.isEmpty(shipmentMethodTypeId)) {
                        shipmentMethodTypeId = "FLAT_RATE";
                    }
                }
                Debug.logInfo("Setting ShipmentMethodTypeId to order:"+shipmentMethodTypeId, module);
                addShipInfo(cart, UtilMisc.toMap("carrierPartyId" , carrierPartyId, "shipmentMethodTypeId", shipmentMethodTypeId), partyInfo[1]);
            }
        }

        SalesOrderPaymentEntity magentoOrderPayment = orderInformation.getPayment();
        String paymentMethod = getPaymentMethodTypeId(magentoOrderPayment, delegator);

        // set the cart payment method
        cart.addPaymentAmount(paymentMethod, new BigDecimal(orderInformation.getGrandTotal()));
        // validate the payment methods
        CheckOutHelper coh = new CheckOutHelper(dispatcher, delegator, cart);
        Map validateResp = coh.validatePaymentMethods();
        if (!ServiceUtil.isSuccess(validateResp)) {
            throw new GeneralException(ServiceUtil.getErrorMessage(validateResp));
        }
        // create the order & process payments
        Map createResp = coh.createOrder(system);
        if (!ServiceUtil.isSuccess(createResp)) {
            return (String) ServiceUtil.getErrorMessage(createResp);
        }
        // approve the order
        String orderId = (String) createResp.get("orderId");
        Map<String, Object> serviceCtx = new HashMap<String, Object>();
        Map<String, Object> serviceResult = new HashMap<String, Object>();
        serviceCtx.put("orderId", orderId);
        serviceCtx.put("statusId", "ORDER_APPROVED");
        serviceCtx.put("setItemStatus", "Y");
        serviceCtx.put("userLogin", system);
        serviceResult = dispatcher.runSync("changeOrderStatus", serviceCtx);

        if(ServiceUtil.isError(serviceResult)) {
            Debug.log("=====Problem in approving the order. Following order is not approved========="+ orderId);
            return "error";
        }
        return "success";
    }


    public static String[] setPartyInfo(String magentoCustomerId, String emailAddress, SalesOrderAddressEntity shipAddr, SalesOrderAddressEntity billAddr, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        String shipCmId = null;
        String billCmId = null;
        String partyId = null;
        GenericValue party = null;
        if(UtilValidate.isNotEmpty(magentoCustomerId)) {
            party = EntityUtil.getFirst(delegator.findList("Party", EntityCondition.makeCondition("externalId", magentoCustomerId), null, null, null, false));
        }
        if (UtilValidate.isNotEmpty(party)) {
            partyId = party.getString("partyId");

            // look for an existing shipping address
            if (UtilValidate.isNotEmpty(shipAddr)) {
            shipCmId = getPostalAddressContactMechId(partyId, getAddressType(SHIPPING_ADDRESS), shipAddr.getStreet(), null,
                    shipAddr.getCity() , getStateGeoId(shipAddr.getRegion(), shipAddr.getCountryId(), delegator), shipAddr.getPostcode() , getCountryGeoId(shipAddr.getCountryId(), delegator), delegator);
                if (UtilValidate.isEmpty(shipCmId)) {
                    shipCmId = createPartyAddress(partyId, shipAddr, delegator, dispatcher);
                    addPurposeToAddress(partyId, shipCmId, SHIPPING_ADDRESS, delegator, dispatcher);
                }
            }
            if (UtilValidate.isNotEmpty(billAddr)) {
                // look for an existing billing address
                billCmId = getPostalAddressContactMechId(partyId, getAddressType(BILLING_ADDRESS), billAddr.getStreet(), null,
                        billAddr.getCity(), getStateGeoId(billAddr.getRegion(), shipAddr.getCountryId(), delegator), billAddr.getPostcode(), getCountryGeoId(billAddr.getCountryId(), delegator), delegator);
                if (UtilValidate.isEmpty(billCmId)) {
                    billCmId = createPartyAddress(partyId, billAddr, delegator, dispatcher);
                    addPurposeToAddress(partyId, billCmId, BILLING_ADDRESS, delegator, dispatcher);
                }
            }
        } else {
        GenericValue system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system"));
        Map<String, Object> serviceCtx = new HashMap<String, Object>();

        // create new party
        if (partyId == null) {
            serviceCtx.put("firstName", shipAddr.getFirstname());
            serviceCtx.put("lastName", shipAddr.getLastname());
            serviceCtx.put("externalId", magentoCustomerId);
            serviceCtx.put("userLogin", system);
            Map<String, Object> personResp = dispatcher.runSync("createPerson", serviceCtx);
            if (!ServiceUtil.isSuccess(personResp)) {
                throw new GeneralException("Unable to create new customer account: " + ServiceUtil.getErrorMessage(personResp));
            }
            partyId = (String) personResp.get("partyId");
            Debug.logInfo("New party created : " + partyId, module);
        }

        serviceCtx.clear();
        serviceCtx.put("partyId", partyId);
        serviceCtx.put("roleTypeId", "CUSTOMER");
        serviceCtx.put("userLogin", system);
        dispatcher.runSync("createPartyRole", serviceCtx);

        if (UtilValidate.isNotEmpty(shipAddr)) {
            shipCmId = createPartyAddress(partyId, shipAddr, delegator, dispatcher);
            addPurposeToAddress(partyId, shipCmId, SHIPPING_ADDRESS, delegator, dispatcher);
        }
        if (UtilValidate.isNotEmpty(billAddr)) {
            billCmId = createPartyAddress(partyId, billAddr, delegator, dispatcher);
            addPurposeToAddress(partyId, billCmId, BILLING_ADDRESS, delegator, dispatcher);
        }
        }
        return new String[] { partyId, shipCmId, billCmId };
    }

    public static String getPostalAddressContactMechId(String partyId, String purposeTypeId, String address1, String address2, String city, 
        String stateProvinceGeoId, String postalCode, String countryGeoId, Delegator delegator) throws GeneralException {
        String contactMechId = null;
        if (address1 == null || city == null || postalCode == null || partyId == null) {
            return contactMechId;
        }

        List<EntityCondition> addrExprs = new ArrayList<EntityCondition>();
        addrExprs.add(EntityCondition.makeCondition("partyId", partyId));
        addrExprs.add(EntityCondition.makeCondition(EntityFunction.UPPER_FIELD("address1"), EntityOperator.EQUALS, EntityFunction.UPPER(address1)));
        addrExprs.add(EntityCondition.makeCondition(EntityFunction.UPPER_FIELD("city"), EntityOperator.EQUALS, EntityFunction.UPPER(city)));
        if (postalCode.length() == 10 && postalCode.indexOf("-") != -1) {
            String[] zipSplit = postalCode.split("-", 2);
            postalCode = zipSplit[0];
            String postalCodeExt = zipSplit[1];
            addrExprs.add(EntityCondition.makeCondition("postalCodeExt", postalCodeExt));
        }
        addrExprs.add(EntityCondition.makeCondition("postalCode", postalCode));

        if (UtilValidate.isNotEmpty(address2)) {
            addrExprs.add(EntityCondition.makeCondition(EntityFunction.UPPER_FIELD("address2"), EntityOperator.EQUALS, EntityFunction.UPPER(address2)));
        }

        if (UtilValidate.isNotEmpty(stateProvinceGeoId)) {
            if ("**".equals(stateProvinceGeoId)) {
                Debug.logWarning("Illegal state code passed!", module);
            } else if ("NA".equals(stateProvinceGeoId)) {
                addrExprs.add(EntityCondition.makeCondition("stateProvinceGeoId", EntityOperator.EQUALS, "_NA_"));
            } else {
                addrExprs.add(EntityCondition.makeCondition("stateProvinceGeoId", stateProvinceGeoId.toUpperCase()));
            }
        } else {
            addrExprs.add(EntityCondition.makeCondition("stateProvinceGeoId", EntityOperator.EQUALS, "_NA_"));
        }

        if (UtilValidate.isNotEmpty(purposeTypeId)) {
            addrExprs.add(EntityCondition.makeCondition("contactMechPurposeTypeId", purposeTypeId));
        }

        if (UtilValidate.isNotEmpty(countryGeoId)) {
            addrExprs.add(EntityCondition.makeCondition("countryGeoId", countryGeoId.toUpperCase()));
        }

        addrExprs.add(EntityCondition.makeConditionDate("fromDate", "thruDate"));
        EntityCondition addrCond = EntityCondition.makeCondition(addrExprs);

        GenericValue partyContactDetailByPurpose = EntityUtil.getFirst(delegator.findList("PartyContactDetailByPurpose", addrCond, null, null, null, false));

        if (UtilValidate.isNotEmpty(partyContactDetailByPurpose)) {
            contactMechId = partyContactDetailByPurpose.getString("contactMechId");
        }

        return contactMechId;

    }

    public static String createPartyAddress(String partyId, SalesOrderAddressEntity addr, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        // check for zip+4
        String postalCode = addr.getPostcode();
        String postalCodeExt = null;
        if (postalCode.length() == 10 && postalCode.indexOf("-") != -1) {
            String[] strSplit = postalCode.split("-", 2);
            postalCode = strSplit[0];
            postalCodeExt = strSplit[1];
        }
        String toName = (addr.getFirstname()+" "+(String)addr.getLastname());
        GenericValue system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system"));

        // prepare the create address map
        Map<String, Object> addrMap = new HashMap<String, Object>();
        addrMap.put("partyId", partyId);
        addrMap.put("toName", toName);
        addrMap.put("address1", addr.getStreet());
        addrMap.put("city", addr.getCity());
        addrMap.put("stateProvinceGeoId", getStateGeoId(addr.getRegion(), addr.getCountryId(), delegator));
        addrMap.put("countryGeoId", getCountryGeoId(addr.getCountryId(), delegator));
        addrMap.put("postalCode", postalCode);
        addrMap.put("postalCodeExt", postalCodeExt);
        addrMap.put("allowSolicitation", "Y");
        addrMap.put("contactMechPurposeTypeId", "GENERAL_LOCATION");
        addrMap.put("userLogin", system); // run as the system user
        
        // invoke the create address service
        Map<String, Object> addrResp = dispatcher.runSync("createPartyPostalAddress", addrMap);
        if (ServiceUtil.isError(addrResp)) {
            throw new GeneralException("Unable to create new customer address record: " +
                    ServiceUtil.getErrorMessage(addrResp));
        }
        String contactMechId = (String) addrResp.get("contactMechId");
        
        Debug.logInfo("Created new address for partyId [" + partyId + "] :" + contactMechId, module);
        return contactMechId;
    }

    public static void addPurposeToAddress(String partyId, String contactMechId, int addrType, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        // convert the int to a purpose type ID
        String contactMechPurposeTypeId = getAddressType(addrType);
        GenericValue system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system"));
        
        
        // check to make sure the purpose doesn't already exist
        EntityCondition condition = EntityCondition.makeCondition(
                EntityCondition.makeCondition("partyId", partyId),
                EntityCondition.makeCondition("contactMechId", contactMechId),
                EntityCondition.makeCondition("contactMechPurposeTypeId", contactMechPurposeTypeId)
                );
        
        List<GenericValue> values = delegator.findList("PartyContactMechPurpose", condition, null, null, null, false);
        if (values == null || values.size() == 0) {
            Map<String, Object> addPurposeMap = new HashMap<String, Object>();
            addPurposeMap.put("contactMechId", contactMechId);
            addPurposeMap.put("partyId", partyId);     
            addPurposeMap.put("contactMechPurposeTypeId", contactMechPurposeTypeId);
            addPurposeMap.put("userLogin", system);
            
            Map<String, Object> addPurposeResp = dispatcher.runSync("createPartyContactMechPurpose", addPurposeMap);
            if (addPurposeResp != null && ServiceUtil.isError(addPurposeResp)) {
                throw new GeneralException(ServiceUtil.getErrorMessage(addPurposeResp));
            }
        }
    }

    public static String getAddressType(int addrType) {
        String contactMechPurposeTypeId = "GENERAL_LOCATION";
        switch(addrType) {
            case SHIPPING_ADDRESS:
                contactMechPurposeTypeId = "SHIPPING_LOCATION";
                break;
            case BILLING_ADDRESS:
                contactMechPurposeTypeId = "BILLING_LOCATION";
                break;
        }
        return contactMechPurposeTypeId;
    }
    
    public static void setContactInfo(ShoppingCart cart, String contactMechPurposeTypeId, String infoString, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        Map<String, Object> lookupMap = new HashMap<String, Object>();
        String cmId = null;
        String entityName = "PartyAndContactMech";
        GenericValue cmLookup = null;
        GenericValue  system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system")); 
        if (contactMechPurposeTypeId.startsWith("PHONE_")) {
            lookupMap.put("partyId", cart.getOrderPartyId());
            lookupMap.put("contactNumber", infoString);
            entityName = "PartyAndTelecomNumber";
        } else if (contactMechPurposeTypeId.endsWith("_EMAIL")) {
            lookupMap.put("partyId", cart.getOrderPartyId());
            lookupMap.put("infoString", infoString);
        } else {
            throw new GeneralException("Invalid contact mech type");
        }
        EntityCondition cond = EntityCondition.makeCondition(
                EntityCondition.makeCondition(lookupMap),
                EntityCondition.makeConditionDate("fromDate", "thruDate")
                );
        try {
            //cmLookup = delegator.findByAnd(entityName, lookupMap, UtilMisc.toList("-fromDate"));
            //cmLookup = EntityUtil.filterByDate(cmLookup);
            cmLookup = EntityUtil.getFirst(delegator.findList(entityName, cond, null, null, null, false));
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
            throw e;
        }

        if (UtilValidate.isNotEmpty(cmLookup)) {
                cmId = cmLookup.getString("contactMechId");
        } else {
            // create it
            lookupMap.put("contactMechPurposeTypeId", contactMechPurposeTypeId);
            lookupMap.put("userLogin", system);
            Map<String, Object> createResp = null;
            if (contactMechPurposeTypeId.startsWith("PHONE_")) {
                try {
                    createResp = dispatcher.runSync("createPartyTelecomNumber", lookupMap);
                } catch (GeneralException e) {
                    Debug.logError(e, module);
                    throw e;
                }
            } else if (contactMechPurposeTypeId.endsWith("_EMAIL")) {
                lookupMap.put("emailAddress", lookupMap.get("infoString"));
                lookupMap.put("allowSolicitation", "Y");
                try {
                    createResp = dispatcher.runSync("createPartyEmailAddress", lookupMap);
                } catch (GeneralException e) {
                    Debug.logError(e, module);
                    throw e;
                }
            }
            if (createResp == null || ServiceUtil.isError(createResp)) {
                throw new GeneralException("Unable to create the request contact mech");
            }

            // get the created ID
            cmId = (String) createResp.get("contactMechId");
        }
        if (cmId != null) {
            cart.addContactMech(contactMechPurposeTypeId, cmId);
        }
    }

    public static void addAdjustments(ShoppingCart cart, Map<String,?> adjustment, Delegator delegator) {
        // handle shipping
        BigDecimal shipAmount = new BigDecimal(adjustment.get("orderShippingAmount").toString());
        GenericValue shipAdj = delegator.makeValue("OrderAdjustment", new HashMap<String, Object>());
        shipAdj.set("orderAdjustmentTypeId", "SHIPPING_CHARGES");
        shipAdj.set("amount", shipAmount);
        cart.addAdjustment(shipAdj);

        // handle tax
        BigDecimal taxAmount = new BigDecimal(adjustment.get("orderTaxAmount").toString());
        GenericValue taxAdj = delegator.makeValue("OrderAdjustment", new HashMap<String, Object>());
        taxAdj.set("orderAdjustmentTypeId", "SALES_TAX");
        taxAdj.set("amount", taxAmount);
        cart.addAdjustment(taxAdj);

        // handle DISCOUNT
        BigDecimal discountAmount = new BigDecimal(adjustment.get("orderDiscountAmount").toString());
        GenericValue discountAdj = delegator.makeValue("OrderAdjustment", new HashMap<String, Object>());
        discountAdj.set("orderAdjustmentTypeId", "DISCOUNT_ADJUSTMENT");
        discountAdj.set("amount", discountAmount);
        cart.addAdjustment(discountAdj);
    }

    public static String getCountryGeoId(String countryGeoCode, Delegator delegator) {
        try {
            EntityCondition condition = EntityCondition.makeCondition(
                    EntityCondition.makeCondition("geoCode", countryGeoCode),
                    EntityCondition.makeCondition("geoTypeId", "COUNTRY")
                    );
            GenericValue geo = EntityUtil.getFirst(delegator.findList("Geo", condition, null, null, null, false));
            if (UtilValidate.isNotEmpty(geo)) {
                return geo.getString("geoId");
            } else {
                return "_NA_";
            }
        } catch (GenericEntityException gee) {
            Debug.logError(gee, module);
        }
        return null;
    }

    public static String getStateGeoId(String stateGeoCode, String countryGeoCode, Delegator delegator) {
        try {
            String countryGeoId = getCountryGeoId(countryGeoCode, delegator);
            EntityCondition condition = EntityCondition.makeCondition(
                    EntityCondition.makeCondition("geoCode", stateGeoCode),
                    EntityCondition.makeCondition("geoTypeId", "STATE")
                    );
            List<GenericValue> geoList = delegator.findList("Geo", condition, null, null, null, false);
            if (UtilValidate.isNotEmpty(geoList)) {
                for(GenericValue geo : geoList) {
                    GenericValue geoAssoc = delegator.findOne("GeoAssoc", true, UtilMisc.toMap("geoId", countryGeoId, "geoIdTo", geo.getString("geoId")));
                    if (UtilValidate.isNotEmpty(geoAssoc)) {
                        return geo.getString("geoId");
                    }
                }
            } else {
                return "_NA_";
            }
        } catch (GenericEntityException gee) {
            Debug.logError(gee, module);
        }
        return null;
    }

    public static void addShipInfo(ShoppingCart cart, Map<String , ?> shipping, String shipContactMechId) {
        String shipmentMethodTypeId = (String) shipping.get("shipmentMethodTypeId");
        String carrierPartyId = (String)shipping.get("carrierPartyId");
        Boolean maySplit = Boolean.FALSE; 
        cart.setShipmentMethodTypeId(0, shipmentMethodTypeId);
        cart.setCarrierPartyId(0, carrierPartyId);
        cart.setMaySplit(0, maySplit);
        cart.setShippingContactMechId(0, shipContactMechId);
    }
    public static void processStateChange(Map<String, ?> info, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        String externalId = info.get("externalId").toString();
        GenericValue order = null;
        GenericValue system = delegator.findOne("UserLogin", false, UtilMisc.toMap("userLoginId", "system"));
        if ("ORDER_CANCELLED".equals(info.get("orderStatus").toString().toUpperCase())) {
            try {
                EntityCondition cond = EntityCondition.makeCondition(
                        EntityCondition.makeCondition("externalId", externalId),
                        EntityCondition.makeCondition("salesChannelEnumId", SALES_CHANNEL)
                        );
                order = EntityUtil.getFirst(delegator.findList("OrderHeader", cond, null, null, null, false));
            } catch (GenericEntityException gee) {
                Debug.logError(gee, module);
            }
            if (UtilValidate.isNotEmpty(order)) {
             // cancel the order
                if (!"ORDER_CANCELLED".equals(order.getString("syncStatusId"))) {
                    dispatcher.runSync("updateOrderHeader", UtilMisc.toMap("orderId", order.getString("orderId"), "syncStatusId", "ORDER_CANCELLED", "userLogin", system));
                }
                if (!"ORDER_CANCELLED".equals(order.getString("statusId"))) {
                    OrderChangeHelper.cancelOrder(dispatcher, system, order.getString("orderId"));
                }
            }
        }
    }
    public static void addItem(ShoppingCart cart, Map<String,?> item, String prodCatalogId, int groupIdx, Delegator delegator, LocalDispatcher dispatcher) throws GeneralException {
        String productId = item.get("productId").toString();
        BigDecimal qty = new BigDecimal(item.get("quantity").toString());
        BigDecimal price = new BigDecimal(item.get("price").toString());
        price = price.setScale(ShoppingCart.scale, ShoppingCart.rounding);
        
        HashMap<String, Object> attrs = new HashMap<String, Object>();
        attrs.put("shipGroup", groupIdx);
        int idx = cart.addItemToEnd(productId, null, qty, null, null, attrs, prodCatalogId, null, dispatcher, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
        ShoppingCartItem cartItem = cart.findCartItem(idx);
        cartItem.setQuantity(qty, dispatcher, cart, true, false);
        cartItem.setExternalId((String)item.get("orderItemId"));
        // locate the price verify it matches the expected price
        BigDecimal cartPrice = cartItem.getBasePrice();
        cartPrice = cartPrice.setScale(ShoppingCart.scale, ShoppingCart.rounding);
        if (price.doubleValue() != cartPrice.doubleValue()) {
            // does not match; honor the price but hold the order for manual review
            cartItem.setIsModifiedPrice(true);
            cartItem.setBasePrice(price);
            cart.setHoldOrder(true);
        }
        // assign the item to its ship group
        cart.setItemShipGroupQty(cartItem, qty, groupIdx);
    }
    public static String getPaymentMethodTypeId (SalesOrderPaymentEntity magentoOrderPayment, Delegator delegator) {
        String paymentMethodTypeId = null;
        if (UtilValidate.isNotEmpty(magentoOrderPayment)) {
            if (UtilValidate.isNotEmpty(magentoOrderPayment.getCcType())) {
                paymentMethodTypeId = "EXT_MAGENTO_CC";
            } else if ("checkmo".equalsIgnoreCase(magentoOrderPayment.getMethod())) {
                paymentMethodTypeId = "EXT_MAGENTO_CHECKMO";
            } else if ("cashondelivery".equalsIgnoreCase(magentoOrderPayment.getMethod())) {
                paymentMethodTypeId = "EXT_MAGENTO_COD";
            }
        }
        if (UtilValidate.isEmpty(paymentMethodTypeId)) {
            paymentMethodTypeId = EntityUtilProperties.getPropertyValue("Magento.properties", "magento.payment.method", "EXT_MAGENTO", delegator);
        }
        return paymentMethodTypeId;
    }
    public static String completeOrderInMagento (LocalDispatcher dispatcher, Delegator delegator, String orderId) {
        try {
            GenericValue orderHeader = delegator.findOne("OrderHeader", false, UtilMisc.toMap("orderId", orderId));
            if (UtilValidate.isNotEmpty(orderHeader)) {
                String orderIncrementId = orderHeader.getString("externalId");
                MagentoClient magentoClient = new MagentoClient(dispatcher, delegator);
                List<GenericValue> orderShipmentList = delegator.findList("OrderShipment", EntityCondition.makeCondition("orderId", orderId), null, null, null, false);
                if (UtilValidate.isNotEmpty(orderShipmentList)) {
                    List<String> shipGroupSeqIdList = EntityUtil.getFieldListFromEntityList(orderShipmentList, "shipGroupSeqId", true);
                    if (UtilValidate.isNotEmpty(shipGroupSeqIdList)) {
                        for (String shipGroupSeqId : shipGroupSeqIdList) {
                            String shipmentId = null;
                            Map<Integer, Double> orderItemQtyMap = new HashMap<Integer, Double>();
                            for (GenericValue orderShipment : orderShipmentList) {
                                if ((orderShipment.getString("shipGroupSeqId")).equals(shipGroupSeqId)) {
                                    if (UtilValidate.isEmpty(shipmentId)) {
                                        shipmentId = orderShipment.getString("shipmentId");
                                    }
                                    GenericValue orderItem = delegator.findOne("OrderItem", false, UtilMisc.toMap("orderId", orderId, "orderItemSeqId", orderShipment.getString("orderItemSeqId")));
                                    Integer externalId = Integer.valueOf(orderItem.getString("externalId"));
                                    orderItemQtyMap.put(externalId, orderShipment.getDouble("quantity"));
                                }
                            }
                            String shipmentIncrementId = magentoClient.createShipment(orderIncrementId, orderItemQtyMap);
                            if (UtilValidate.isNotEmpty(shipmentIncrementId)) {
                                List<GenericValue> shipmentPackageRouteSegList = delegator.findList("ShipmentPackageRouteSeg", EntityCondition.makeCondition("shipmentId", shipmentId), UtilMisc.toSet("shipmentRouteSegmentId", "trackingCode"), null, null, false);
                                if (UtilValidate.isNotEmpty(shipmentPackageRouteSegList)) {
                                    String carrierTitle = null;
                                    for (GenericValue shipmentPackageRouteSeg : shipmentPackageRouteSegList) {
                                        GenericValue shipmentRoutSegment = delegator.findOne("ShipmentRouteSegment", false, UtilMisc.toMap("shipmentId", shipmentId, "shipmentRouteSegmentId", shipmentPackageRouteSeg.getString("shipmentRouteSegmentId")));
                                        String trackingCode = shipmentPackageRouteSeg.getString("trackingCode");
                                        String carrierPartyId = shipmentRoutSegment.getString("carrierPartyId");
                                        if (UtilValidate.isEmpty(trackingCode)) {
                                            continue;
                                        }
                                        if (UtilValidate.isEmpty(carrierPartyId) || "_NA_".equals(carrierPartyId)) {
                                            carrierPartyId = "custom";
                                            carrierTitle = "Flat Rate";
                                        } else {
                                            GenericValue carrier = delegator.findOne("PartyGroup", false, UtilMisc.toMap("partyId", carrierPartyId));
                                            if (UtilValidate.isNotEmpty(carrier)) {
                                                carrierTitle = carrier.getString("groupName");
                                            }
                                        }
                                        int istrackingCodeAdded = magentoClient.addTrack(shipmentIncrementId, carrierPartyId.toLowerCase(), carrierTitle, trackingCode);
                                        if (1 == istrackingCodeAdded) {
                                            Debug.logInfo("Tracking code is added successfully in Magento side for shipment # "+shipmentId+".", module);
                                        }
                                    }
                                }

                                String invoiceIncrementId = magentoClient.createInvoice(orderIncrementId, orderItemQtyMap);
                                if (UtilValidate.isNotEmpty(invoiceIncrementId)) {
                                    Debug.log("order #"+orderIncrementId+" invoiceIncrementId="+invoiceIncrementId);
                                }
                            }
                        }
                    }
                }
            }
        } catch (GenericEntityException gee) {
            Debug.logError(gee.getMessage(), module);
            return null;
        }
        return "Success";
    }
    public static Filters prepareSalesOrderFilters(String magOrderId, String statusId, Timestamp fromDate, Timestamp thruDate, String productStoreId) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String createdFrom = null;
        String createdTo = null;

        Filters filters = new Filters();
        ComplexFilterArray complexFilterArray = new ComplexFilterArray();
        ComplexFilter complexFilter = new ComplexFilter();

        if (UtilValidate.isNotEmpty(fromDate)) {
            Date from = (Date) fromDate;
            createdFrom = df.format(from);
        }
        if (UtilValidate.isNotEmpty(thruDate)) {
            Date thru = (Date) thruDate;
            createdTo = df.format(thru);
        }

        AssociativeEntity statusCond = new AssociativeEntity();
        statusCond.setKey("eq");
        statusCond.setValue(statusId);
        complexFilter.setKey("status");
        complexFilter.setValue(statusCond);

        AssociativeEntity createdDateCond = new AssociativeEntity();
        if (UtilValidate.isNotEmpty(createdFrom)) {
            createdDateCond.setKey("from");
            createdDateCond.setValue(createdFrom);
        }
        if (UtilValidate.isNotEmpty(createdTo)) {
            createdDateCond.setKey("to");
            createdDateCond.setValue(createdTo);
        }
        if (UtilValidate.isNotEmpty(createdFrom) || UtilValidate.isNotEmpty(createdTo)) {
            complexFilter.setKey("created_at");
            complexFilter.setValue(createdDateCond);
        }

        if (UtilValidate.isNotEmpty(magOrderId)) {
            AssociativeEntity orderIncrementIdCond = new AssociativeEntity();
            orderIncrementIdCond.setKey("eq");
            orderIncrementIdCond.setValue(magOrderId);
            complexFilter.setKey("increment_id");
            complexFilter.setValue(orderIncrementIdCond);
        }

        if (UtilValidate.isNotEmpty(productStoreId)) {
            AssociativeEntity productStoreIdCond = new AssociativeEntity();
            productStoreIdCond.setKey("eq");
            productStoreIdCond.setValue(productStoreId);
            complexFilter.setKey("store_id");
            complexFilter.setValue(productStoreIdCond);
        }
        complexFilterArray.getComplexObjectArray().add(complexFilter);
        filters.setComplexFilter(complexFilterArray);

        return filters;
    }

    public static String modelParamNameToCsvColumnName(String keyName, char separator) {
        if (keyName == null) return null;

        StringBuilder fieldName = new StringBuilder(keyName.length());
        for (int i=0; i < keyName.length(); i++) {
            char ch = keyName.charAt(i);
            if (Character.isUpperCase(ch) && i == 0) {
                fieldName.append(Character.toLowerCase(ch));
            } else if (Character.isUpperCase(ch)) {
                fieldName.append(separator);
                fieldName.append(Character.toLowerCase(ch));
            } else {
                fieldName.append(Character.toLowerCase(ch));
            }
        }
        return fieldName.toString();
    }

    public static String getCsvheader (List<String> paramNameList) {
        if (UtilValidate.isEmpty(paramNameList)) {
            return null;
        }
        String header = "";
        for (String paramName : paramNameList) {
            String convertedParamName = modelParamNameToCsvColumnName(paramName, '-');
            header = header.concat("\"" + convertedParamName + "\"");
            header = header.concat(",");
        }
        return header;
    }
    public static void createMagentoIntegrationConciliationCSV (List<Map<String, Object>> reportList) {
        String header = "";
        try {
            if (UtilValidate.isNotEmpty(reportList)) {
                List<String> csvFieldList = new ArrayList<String>(); 
                csvFieldList.addAll((reportList.get(0)).keySet());
                if (UtilValidate.isNotEmpty(csvFieldList)) {
                    header = MagentoHelper.getCsvheader(csvFieldList);
                } else {
                    Debug.logInfo("No CSV field list found.", module);
                }
                if (UtilValidate.isNotEmpty(header)) {
                    String outputLocation = System.getProperty("ofbiz.home") + "/runtime/magento/";
                    File outputDir = new File(outputLocation);
                    if (!outputDir.exists()) {
                        outputDir.mkdir();
                    }
                    String fileName = "/".concat("MagentoIntegrationConciliation").concat(".csv");
                    String csvFileLocation = outputLocation.concat(fileName);
                    Writer outFile = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFileLocation)));
                    outFile.write(header);

                    String row = null;
                    String fieldValue = null;

                    for (Map report : reportList) {
                        row = "";
                        for (String fieldName : csvFieldList) {
                            if (UtilValidate.isNotEmpty(report.get(fieldName))) {
                                fieldValue = report.get(fieldName).toString();
                                fieldValue = fieldValue.replaceAll("\"", "&quot;");
                                row = row.concat("\"" +fieldValue+ "\"");
                            }
                            row = row.concat(",");
                        }
                        outFile.append("\n");
                        outFile.append(row);
                    }
                    outFile.close();
                }
            }
        } catch (IOException e) {
            Debug.logError("I/O error while reading from file: " + e.getMessage(), module);
        }
    }
    public static List<Map<String, Object>> getVariance(LocalDispatcher dispatcher, Delegator delegator, String productStoreId) {
        int mageTotalOrders = 0;
        int mageTotalCompletedOrders = 0;
        int mageTotalCancelledOrders = 0;
        int mageTotalHeldOrders = 0;

        int ofbizTotalOrders = 0;
        int ofbizTotalCancelledOrders = 0;
        int ofbizTotalCompletedOrders = 0;
        int ofbizTotalHeldOrders = 0;
        List<Map<String, Object>> varianceList  = new ArrayList<Map<String, Object>>();
        Map<String, Object> condMap = new HashMap<String, Object>();

        try {
            condMap.put("serviceName", "importPendingOrdersFromMagento");
            condMap.put("statusId", "SERVICE_PENDING");
            GenericValue ipoJob = EntityUtil.getFirst(delegator.findList("JobSandbox", EntityCondition.makeCondition(condMap), UtilMisc.toSet("previousJobId"), null, null, false));
            if (UtilValidate.isNotEmpty(ipoJob) && UtilValidate.isNotEmpty(ipoJob.getString("previousJobId"))) {
                ipoJob = delegator.findOne("JobSandbox", false, UtilMisc.toMap("jobId", ipoJob.getString("previousJobId")));
            }
            condMap.put("serviceName", "importCancelledOrdersFromMagento");
            GenericValue icoJob = EntityUtil.getFirst(delegator.findList("JobSandbox", EntityCondition.makeCondition(condMap), UtilMisc.toSet("previousJobId"), null, null, false));
            if (UtilValidate.isNotEmpty(icoJob) && UtilValidate.isNotEmpty(icoJob.getString("previousJobId"))) {
                icoJob = delegator.findOne("JobSandbox", false, UtilMisc.toMap("jobId", icoJob.getString("previousJobId")));
            }
            condMap.put("serviceName", "importHeldOrdersFromMagento");
            GenericValue ihoJob = EntityUtil.getFirst(delegator.findList("JobSandbox", EntityCondition.makeCondition(condMap), UtilMisc.toSet("previousJobId"), null, null, false));
            if (UtilValidate.isNotEmpty(ihoJob) && UtilValidate.isNotEmpty(ihoJob.getString("previousJobId"))) {
                ihoJob = delegator.findOne("JobSandbox", false, UtilMisc.toMap("jobId", ihoJob.getString("previousJobId")));
            }
            if (UtilValidate.isNotEmpty(ipoJob) || UtilValidate.isNotEmpty(icoJob) || UtilValidate.isNotEmpty(ihoJob)) {
                Timestamp fromDate = null;
                Timestamp ipoJobFinishDateTime = null;
                Timestamp icoJobFinishDateTime = null;
                Timestamp ihoJobFinishDateTime = null;
                if (UtilValidate.isNotEmpty(ipoJob) && UtilValidate.isNotEmpty(ipoJob.getTimestamp("finishDateTime"))) {
                ipoJobFinishDateTime = ipoJob.getTimestamp("finishDateTime");
                fromDate = UtilDateTime.getDayStart(ipoJobFinishDateTime);
                }
                if (UtilValidate.isNotEmpty(icoJob) && UtilValidate.isNotEmpty(icoJob.getTimestamp("finishDateTime"))) {
                icoJobFinishDateTime = icoJob.getTimestamp("finishDateTime");
                }
                if (UtilValidate.isNotEmpty(ihoJob) && UtilValidate.isNotEmpty(ihoJob.getTimestamp("finishDateTime"))) {
                ihoJobFinishDateTime = ihoJob.getTimestamp("finishDateTime");
                }
                MagentoClient magentoClient = new MagentoClient(dispatcher, delegator);
                Filters filters = MagentoHelper.prepareSalesOrderFilters(null, null, fromDate, null, productStoreId);
                List<SalesOrderListEntity> salesOrders = magentoClient.getSalesOrderList(filters);
                if (UtilValidate.isNotEmpty(salesOrders)) {
                    for (SalesOrderListEntity salesOrder : salesOrders) {
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                        if (UtilValidate.isNotEmpty(salesOrder.getCreatedAt())) {
                        Date date = dateFormat.parse(salesOrder.getCreatedAt());
                        dateFormat.setTimeZone(TimeZone.getDefault());
                        date = dateFormat.parse(dateFormat.format(date));
                        Timestamp createdAt = UtilDateTime.toTimestamp(date);
                        if (UtilValidate.isNotEmpty(ipoJobFinishDateTime) && ipoJobFinishDateTime.after(createdAt)) {
                            if ("completed".equals(salesOrder.getStatus())) {
                                mageTotalCompletedOrders++;
                            }
                            mageTotalOrders++;
                        }
                        if ("canceled".equals(salesOrder.getStatus()) && UtilValidate.isNotEmpty(icoJobFinishDateTime) && icoJobFinishDateTime.after(createdAt)) {
                            mageTotalCancelledOrders++;
                        } else if ("holded".equals(salesOrder.getStatus()) && UtilValidate.isNotEmpty(ihoJobFinishDateTime) && ihoJobFinishDateTime.after(createdAt)) {
                            mageTotalHeldOrders++;
                        }
                        }
                    }

                    EntityCondition cond = EntityCondition.makeCondition(
                            EntityCondition.makeCondition("orderDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate),
                            EntityCondition.makeCondition("orderDate", EntityOperator.LESS_THAN_EQUAL_TO, ipoJobFinishDateTime)
                            );
                    List<GenericValue> allOrderList = delegator.findList("OrderHeader", cond, null, null, null, false);
                    List<GenericValue> completedOrderList = EntityUtil.filterByAnd(allOrderList, UtilMisc.toMap("statusId", "ORDER_COMPLETED"));
                    cond = EntityCondition.makeCondition(
                            EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, "ORDER_HOLD"),
                            EntityCondition.makeCondition("orderDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate),
                            EntityCondition.makeCondition("orderDate", EntityOperator.LESS_THAN_EQUAL_TO, ihoJobFinishDateTime)
                            );
                    List<GenericValue> heldOrderList = delegator.findList("OrderHeader", cond, null, null, null, false);
                    cond = EntityCondition.makeCondition(
                            EntityCondition.makeCondition("statusId", "ORDER_CANCELLED"),
                            EntityCondition.makeCondition("orderDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate),
                            EntityCondition.makeCondition("orderDate", EntityOperator.LESS_THAN_EQUAL_TO, icoJobFinishDateTime)
                            );
                    List<GenericValue> cancelledOrderList = delegator.findList("OrderHeader", cond, null, null, null, false);
                    ofbizTotalCompletedOrders = completedOrderList.size();
                    ofbizTotalCancelledOrders = cancelledOrderList.size();
                    ofbizTotalHeldOrders = heldOrderList.size();
                    ofbizTotalOrders = allOrderList.size();

                    if ((ofbizTotalOrders != mageTotalOrders) || (ofbizTotalCancelledOrders != mageTotalCancelledOrders) || (ofbizTotalCompletedOrders != mageTotalCompletedOrders) || (ofbizTotalHeldOrders != mageTotalHeldOrders)) {
                        Debug.logInfo("Sales order synchronization process is inconsistent.", module);
                        Debug.logInfo("Total orders created in Magento: "+mageTotalOrders+" in OFBiz "+ofbizTotalOrders, module);
                        Debug.logInfo("Total completed orders in Magento: "+mageTotalCompletedOrders+" in OFBiz "+ofbizTotalCompletedOrders, module);
                        Debug.logInfo("Total cancelled order in Magento: "+mageTotalCancelledOrders+" in OFBiz "+ofbizTotalCancelledOrders, module);
                        Debug.logInfo("Total held order in Magento: "+mageTotalHeldOrders+" in OFBiz "+ofbizTotalHeldOrders, module);

                        int variance = 0;
                        variance = ofbizTotalOrders - mageTotalOrders;
                        if (variance < 0) {
                            variance = -variance;
                        }
                        varianceList.add(UtilMisc.<String, Object>toMap("order", "All Orders", "inOfbiz", ofbizTotalOrders, "inMagento", mageTotalOrders, "variance", variance));

                        variance = ofbizTotalCompletedOrders - mageTotalCompletedOrders;
                        if (variance < 0) {
                            variance = -variance;
                        }
                        varianceList.add(UtilMisc.<String, Object>toMap("order", "Completed Orders", "inOfbiz", ofbizTotalCompletedOrders, "inMagento", mageTotalCompletedOrders, "variance", variance));

                        variance = ofbizTotalCancelledOrders - mageTotalCancelledOrders;
                        if (variance < 0) {
                            variance = -variance;
                        }
                        varianceList.add(UtilMisc.<String, Object>toMap("order", "Cancelled Orders", "inOfbiz", ofbizTotalCancelledOrders, "inMagento", mageTotalCancelledOrders, "variance", variance));

                        variance = ofbizTotalHeldOrders - mageTotalHeldOrders;
                        if (variance < 0) {
                            variance = -variance;
                        }
                        varianceList.add(UtilMisc.<String, Object>toMap("order", "Held Orders", "inOfbiz", ofbizTotalHeldOrders, "inMagento", mageTotalHeldOrders, "variance", variance));
                    }
                }
            }
        } catch (GenericEntityException gee) {
            Debug.logInfo(gee.getMessage(), module);
        } catch (ParseException pe) {
            Debug.logInfo(pe.getMessage(), module);
            pe.printStackTrace();
        }
        return varianceList;
    }
    public static Map<String, String> getMapForContactNumber(String phoneNumber) {
        String countryCode = "1";
        String areaCode = null;
        String contactNumber = null;
        String reversePhoneNumber = new StringBuffer(phoneNumber).reverse().toString().replaceAll("\\D", "");
        if(reversePhoneNumber.length() > 7) {
            contactNumber = new StringBuffer(reversePhoneNumber.substring(0, 7)).reverse().toString();
            reversePhoneNumber = reversePhoneNumber.replaceFirst(reversePhoneNumber.substring(0, 7), "");
            if(reversePhoneNumber.length() > 3) {
                areaCode = new StringBuffer(reversePhoneNumber.substring(0, 3)).reverse().toString();
                countryCode = new StringBuffer(reversePhoneNumber.substring(3)).reverse().toString();
            } else {
                areaCode = new StringBuffer(reversePhoneNumber).reverse().toString();
            }
        } else {
            contactNumber = new StringBuffer(reversePhoneNumber).reverse().toString();
        }
        return UtilMisc.toMap(
                "countryCode", countryCode,
                "areaCode", areaCode,
                "contactNumber", contactNumber);
    }
    public static GenericValue getMagentoProductStore(Delegator delegator) {
        try {
            GenericValue magentoConfiguration = EntityUtil.getFirst(delegator.findList("MagentoConfiguration", EntityCondition.makeCondition("enumId", EntityOperator.EQUALS, "MAGENTO_SALE_CHANNEL"), null, null, null, false));
            if (UtilValidate.isNotEmpty(magentoConfiguration) && UtilValidate.isNotEmpty(magentoConfiguration.getString("productStoreId"))) {
                GenericValue productStore = ProductStoreWorker.getProductStore(magentoConfiguration.getString("productStoreId"), delegator);
                if (UtilValidate.isNotEmpty(productStore)) {
                    return productStore;
                }
            }
        } catch (GenericEntityException gee) {
            Debug.logInfo(gee.getMessage(), module);
            return null;
        }
       return  null;
    }
    public static List<GenericValue> getMagentoProductStoreList(Delegator delegator) {
        try {
            GenericValue magentoConfiguration = EntityUtil.getFirst(delegator.findList("MagentoConfiguration", EntityCondition.makeCondition("enumId", EntityOperator.EQUALS, "MAGENTO_SALE_CHANNEL"), null, null, null, false));
            if (UtilValidate.isNotEmpty(magentoConfiguration) && UtilValidate.isNotEmpty(magentoConfiguration.getString("productStoreId"))) {
                List<String> magentoPoductStoreIds = EntityUtil.getFieldListFromEntityList(delegator.findList("MagentoProductStore", null, null, null, null, false), "productStoreId", true);
                if (UtilValidate.isNotEmpty(magentoPoductStoreIds)) {
                    List<GenericValue> productStoreList = delegator.findList("ProductStore", EntityCondition.makeCondition("productStoreId", EntityOperator.IN, magentoPoductStoreIds), null, null, null, false);
                    if (UtilValidate.isNotEmpty(productStoreList)) {
                        return productStoreList;
                    }
                }
            }
        } catch (GenericEntityException gee) {
            Debug.logInfo(gee.getMessage(), module);
            return null;
        }
        return  null;
    }
    public static URL getTempDataFileUrlToImport (Delegator delegator, String partyId) {
        try {
            String pathString = "file:"+System.getProperty("ofbiz.home")+"/hot-deploy/magento/data/GlAccountData.xml";
            URL templateUrl = new URL(pathString);
            InputStream templateStream = templateUrl.openStream();
            InputStreamReader templateReader = new InputStreamReader(templateStream, "UTF-8");
            BufferedReader inFile = new BufferedReader(templateReader);
            String tempDir = System.getProperty("ofbiz.home")+"/runtime/magento";
            File fileOut = new File(tempDir);
            if (!fileOut.exists()) {
                fileOut.mkdir();
            }
            FileOutputStream fos = new FileOutputStream(new File(fileOut, "TempData.xml"));
            OutputStreamWriter osw = new OutputStreamWriter(fos);
            BufferedWriter outFile = new BufferedWriter(osw);

            String line = null;
            while (null != (line = inFile.readLine())) {
                line = line.replaceAll("ORGPARTYID", partyId);
                line = line.replaceAll("FROMDATE", UtilDateTime.nowDateString("yyyy-MM-dd HH:mm:ss"));
                outFile.write(line);
            }
            outFile.close();
            String outputPathString = "file:"+System.getProperty("ofbiz.home")+"/runtime/magento/TempData.xml";
            URL outputPath = new URL(outputPathString);
            return outputPath;
        } catch (IOException ioe) {
            Debug.logInfo(ioe.getMessage(), module);
            return null;
        }
    }
    public static GenericValue getMagentoConfiguration(Delegator delegator) {
        try {
            GenericValue magentoConfiguration = EntityUtil.getFirst(delegator.findList("MagentoConfiguration", EntityCondition.makeCondition("enumId", EntityOperator.EQUALS, "MAGENTO_SALE_CHANNEL"), null, null, null, true));
            if (UtilValidate.isNotEmpty(magentoConfiguration)) {
                return magentoConfiguration;
            }
        } catch (GenericEntityException gee) {
            Debug.logInfo(gee.getMessage(), module);
        }
        return null;
    }

    public static GenericValue getProductCategory(Delegator delegator, String productCategoryId) {
        GenericValue productCategory = null;
        try {
            productCategory = delegator.findOne("ProductCategory", UtilMisc.toMap("productCategoryId", productCategoryId), false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error in finding product category", module);
        }
        return productCategory;
    }

    public static String getProdCatalogId(Delegator delegator, String productCategoryId) {
        String prodCatalogId = null;
        try {
            GenericValue productCategory = getProductCategory(delegator, productCategoryId);
            if(UtilValidate.isNotEmpty(productCategory)) {
                String topParentCategoryId = null;
                String primaryParentCategoryId = (String) productCategory.get("primaryParentCategoryId");
                while (UtilValidate.isNotEmpty(primaryParentCategoryId)) {
                    productCategory = getProductCategory(delegator, primaryParentCategoryId);
                    primaryParentCategoryId = (String) productCategory.get("primaryParentCategoryId");
                }
                topParentCategoryId = (String) productCategory.get("productCategoryId");

                EntityConditionList<EntityCondition> conditions = EntityCondition.makeCondition(UtilMisc.toList(
                        EntityCondition.makeConditionDate("fromDate", "thruDate"),
                        EntityCondition.makeCondition("productCategoryId", EntityOperator.EQUALS, topParentCategoryId)),
                        EntityOperator.AND);

                List<GenericValue> prodCatalogCategories = delegator.findList("ProdCatalogCategory", conditions, null, null, null, false);
                if(UtilValidate.isNotEmpty(prodCatalogCategories)) {
                    GenericValue prodCatalogCategory = prodCatalogCategories.get(0);
                    prodCatalogId = prodCatalogCategory.getString("prodCatalogId");
                }
            }
        } catch (GenericEntityException e) {
            Debug.logInfo(e.getMessage(), module);
        }
        return prodCatalogId;
    }
}