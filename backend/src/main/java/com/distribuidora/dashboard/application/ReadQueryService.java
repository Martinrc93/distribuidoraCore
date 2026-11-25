package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReadQueryService {
    public enum StockFilter { ALL, POSITIVE, NEGATIVE }

    private final DashboardReadQueryService dashboardReads;
    private final CustomerCatalogReadQueryService customerCatalogReads;
    private final CommercialReadQueryService commercialReads;
    private final AdminReadQueryService adminReads;

    public ReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this(
            new DashboardReadQueryService(jdbc, currentUser),
            new CustomerCatalogReadQueryService(jdbc, currentUser),
            new CommercialReadQueryService(jdbc, currentUser),
            new AdminReadQueryService(jdbc, currentUser));
    }

    @Autowired
    public ReadQueryService(DashboardReadQueryService dashboardReads,
                            CustomerCatalogReadQueryService customerCatalogReads,
                            CommercialReadQueryService commercialReads,
                            AdminReadQueryService adminReads) {
        this.dashboardReads = Objects.requireNonNull(dashboardReads, "dashboardReads");
        this.customerCatalogReads = Objects.requireNonNull(customerCatalogReads, "customerCatalogReads");
        this.commercialReads = Objects.requireNonNull(commercialReads, "commercialReads");
        this.adminReads = Objects.requireNonNull(adminReads, "adminReads");
    }

    public Map<String, Object> dashboard(LocalDate dateMin, LocalDate dateMax) {
        return dashboardReads.dashboard(dateMin, dateMax);
    }

    public PageResponse<Map<String, Object>> dashboardSellerOrders(UUID sellerId, boolean unassigned,
            LocalDate dateMin, LocalDate dateMax, int page, int size) {
        return dashboardReads.dashboardSellerOrders(sellerId, unassigned, dateMin, dateMax, page, size);
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search) {
        return customerCatalogReads.customers(page, size, search);
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search,
            UUID sellerId, boolean hasBalance, String status) {
        return customerCatalogReads.customers(page, size, search, sellerId, hasBalance, status);
    }

    /** {@code kind} filters regular customers or resellers; {@code resellerId}, the customers of a reseller. */
    public PageResponse<Map<String, Object>> customers(int page, int size, String search,
            UUID sellerId, boolean hasBalance, String status, String kind, UUID resellerId) {
        return customerCatalogReads.customers(page, size, search, sellerId, hasBalance, status, kind, resellerId);
    }

    public Map<String, Object> customerFilterOptions() {
        return customerCatalogReads.customerFilterOptions();
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search) {
        return customerCatalogReads.products(page, size, search);
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search, boolean includeStock) {
        return customerCatalogReads.products(page, size, search, includeStock);
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search, boolean includeStock,
            UUID brandId, UUID categoryId, StockFilter stock) {
        return customerCatalogReads.products(page, size, search, includeStock, brandId, categoryId, stock);
    }

    public PageResponse<Map<String, Object>> inventory(int page, int size, String search) {
        return customerCatalogReads.inventory(page, size, search);
    }

    public PageResponse<Map<String, Object>> movements(UUID productId, int page, int size) {
        return customerCatalogReads.movements(productId, page, size);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status) {
        return commercialReads.orders(page, size, search, status);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax) {
        return commercialReads.orders(page, size, search, status, dateMin, dateMax);
    }

    /**
     * Orders list. {@code createdBy} is ADMIN or SELLER (who registered the order, snapshot at confirmation);
     * {@code paidUndelivered} keeps confirmed orders whose sale is fully paid but not delivered yet.
     */
    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax, String createdBy, boolean paidUndelivered) {
        return commercialReads.orders(page, size, search, status, dateMin, dateMax, createdBy, paidUndelivered);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax, String createdBy, boolean paidUndelivered,
            String fulfillmentStatus, boolean reviewPending) {
        return commercialReads.orders(page, size, search, status, dateMin, dateMax, createdBy, paidUndelivered,
            fulfillmentStatus, reviewPending);
    }

    public Map<String, Object> lastCustomerOrder(UUID customerId) {
        return commercialReads.lastCustomerOrder(customerId);
    }

    /** Open debts owed by the customer's account; the scope fallback uses each order's recipient. */
    public PageResponse<Map<String, Object>> customerDebts(UUID customerId, int page, int size) {
        return customerCatalogReads.customerDebts(customerId, page, size);
    }

    public Map<String, Object> orderDetail(UUID orderId) {
        return commercialReads.orderDetail(orderId);
    }

    public Map<String, Object> saleDetail(UUID saleId) {
        return commercialReads.saleDetail(saleId);
    }

    public Map<String, Object> orderDetailByNumber(String orderNumber) {
        return commercialReads.orderDetailByNumber(orderNumber);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search) {
        return commercialReads.sales(page, size, search);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search,
            UUID customerId, UUID sellerId, boolean pendingBalance) {
        return commercialReads.sales(page, size, search, customerId, sellerId, pendingBalance);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search,
            UUID customerId, UUID sellerId, boolean pendingBalance, LocalDate dateMin, LocalDate dateMax) {
        return commercialReads.sales(page, size, search, customerId, sellerId, pendingBalance, dateMin, dateMax);
    }

    /**
     * {@code customerId} filters the customer that received the order; {@code accountCustomerId}, the account
     * that owes the sale (a reseller owes the orders of its customers).
     */
    public PageResponse<Map<String, Object>> sales(int page, int size, String search, UUID customerId,
            UUID accountCustomerId, UUID sellerId, boolean pendingBalance, LocalDate dateMin, LocalDate dateMax) {
        return commercialReads.sales(page, size, search, customerId, accountCustomerId, sellerId, pendingBalance, dateMin, dateMax);
    }

    public Map<String, Object> saleFilterOptions() {
        return commercialReads.saleFilterOptions();
    }

    public PageResponse<Map<String, Object>> payments(int page, int size, String search) {
        return commercialReads.payments(page, size, search);
    }

    public PageResponse<Map<String, Object>> auditEvents(int page, int size, String search) {
        return adminReads.auditEvents(page, size, search);
    }

    public PageResponse<Map<String, Object>> users(int page, int size, String search) {
        return adminReads.users(page, size, search);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size) {
        return adminReads.sellers(page, size);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size, String search, String status) {
        return adminReads.sellers(page, size, search, status);
    }

    public PageResponse<Map<String, Object>> commercialProfiles(
            int page, int size, String search, String status, String kind, boolean canViewResellers) {
        return adminReads.commercialProfiles(page, size, search, status, kind, canViewResellers);
    }

    public Map<String, Object> sellerDetail(UUID id) {
        return adminReads.sellerDetail(id);
    }
}
