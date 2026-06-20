package com.pasadita.pos.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketDTO {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private String employeePhone;
    private Long deliveryOrderId;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private BigDecimal customerDiscount;
    private String deliveryAddress;
    private Long paymentMethodId;
    private String paymentMethodName;
    private LocalDateTime datetime;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal total;
    private BigDecimal amountTendered;
    private BigDecimal changeDue;
    private boolean paid;
    private String notes;
    private List<SaleDetailDTO> saleDetails;

    public TicketDTO() {
    }

    public BigDecimal getCustomerDiscount() {
        return customerDiscount;
    }

    public void setCustomerDiscount(BigDecimal customerDiscount) {
        this.customerDiscount = customerDiscount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getEmployeePhone() {
        return employeePhone;
    }

    public void setEmployeePhone(String employeePhone) {
        this.employeePhone = employeePhone;
    }

    public Long getDeliveryOrderId() {
        return deliveryOrderId;
    }

    public void setDeliveryOrderId(Long deliveryOrderId) {
        this.deliveryOrderId = deliveryOrderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public Long getPaymentMethodId() {
        return paymentMethodId;
    }

    public void setPaymentMethodId(Long paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public String getPaymentMethodName() {
        return paymentMethodName;
    }

    public void setPaymentMethodName(String paymentMethodName) {
        this.paymentMethodName = paymentMethodName;
    }

    public LocalDateTime getDatetime() {
        return datetime;
    }

    public void setDatetime(LocalDateTime datetime) {
        this.datetime = datetime;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public void setAmountTendered(BigDecimal amountTendered) {
        this.amountTendered = amountTendered;
    }

    public BigDecimal getChangeDue() {
        return changeDue;
    }

    public void setChangeDue(BigDecimal changeDue) {
        this.changeDue = changeDue;
    }

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<SaleDetailDTO> getSaleDetails() {
        return saleDetails;
    }

    public void setSaleDetails(List<SaleDetailDTO> saleDetails) {
        this.saleDetails = saleDetails;
    }

    @Override
    public String toString() {
        return "TicketDTO{" +
                "id=" + id +
                ", employeeId=" + employeeId +
                ", employeeName='" + employeeName + '\'' +
                ", employeePhone='" + employeePhone + '\'' +
                ", deliveryOrderId=" + deliveryOrderId +
                ", customerId=" + customerId +
                ", customerName='" + customerName + '\'' +
                ", customerPhone='" + customerPhone + '\'' +
                ", customerDiscount=" + customerDiscount +
                ", deliveryAddress='" + deliveryAddress + '\'' +
                ", paymentMethodId=" + paymentMethodId +
                ", paymentMethodName='" + paymentMethodName + '\'' +
                ", datetime=" + datetime +
                ", subtotal=" + subtotal +
                ", discountAmount=" + discountAmount +
                ", total=" + total +
                ", amountTendered=" + amountTendered +
                ", changeDue=" + changeDue +
                ", paid=" + paid +
                ", notes='" + notes + '\'' +
                ", saleDetails=" + saleDetails +
                '}';
    }
}

