package com.distribuidora.order;

import com.distribuidora.dashboard.application.ReadQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.distribuidora.testinfra.SharedPostgres;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.distribuidora.shared.security.TestCurrentUserAccess;

class SharedOrderSaleNumberMigrationTest {
    private static final SharedPostgres.Database POSTGRES = SharedPostgres.databaseFor(SharedOrderSaleNumberMigrationTest.class);

    @Test
    void migratesSwappedAndPrefixedNumbersWithoutChangingCommercialDataOrReferences() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("56").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID customer = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID paidOrder = UUID.randomUUID();
        UUID paidSale = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id, business_name, created_at) values (?, 'Migration customer', now())", customer);
        jdbc.update("insert into catalog.products(id, name, cost, created_at) values (?, 'Product', 1, now())", product);
        var numbers = List.of("000100001", "000100002", "000100003");
        var oldNumbers = List.of("000100002", "000100001", "V-00000012");
        var statuses = List.of("DELIVERED", "CANCELLED", "CONFIRMED");
        for (int index = 0; index < numbers.size(); index++) {
            UUID order = index == 0 ? paidOrder : UUID.randomUUID();
            UUID sale = index == 0 ? paidSale : UUID.randomUUID();
            jdbc.update("""
                insert into orders.orders(id, order_number, customer_id, status, subtotal, total, created_at)
                values (?, ?, ?, ?, 10, 10, now())
                """, order, numbers.get(index), customer, statuses.get(index));
            jdbc.update("""
                insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, account_customer_id)
                values (?, ?, ?, ?, ?, 10, ?, now(), ?)
                """, sale, oldNumbers.get(index), order, customer, statuses.get(index), index == 0 ? 5 : 0, customer);
        }
        jdbc.update("""
            insert into orders.order_items(id, order_id, product_id, product_name, quantity, unit_price, line_total, price_list_code, line_discount_percent)
            values (?, ?, ?, 'Product', 1, 10, 10, 'GENERAL', 0)
            """, UUID.randomUUID(), paidOrder, product);
        jdbc.update("""
            insert into sale.sale_items(id, sale_id, product_id, product_name, quantity, unit_price, line_total, price_list_code, line_discount_percent)
            values (?, ?, ?, 'Product', 1, 10, 10, 'GENERAL', 0)
            """, UUID.randomUUID(), paidSale, product);
        jdbc.update("""
            insert into payment.payments(id, sale_id, customer_id, amount, method, created_at)
            values (?, ?, ?, 5, 'CASH', now())
            """, UUID.randomUUID(), paidSale, customer);
        jdbc.update("""
            insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at)
            values (?, ?, ?, 'DEBIT', 5, now())
            """, UUID.randomUUID(), customer, paidSale);
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 9, now())", product);
        jdbc.update("""
            insert into inventory.stock_movements(id, product_id, movement_type, quantity, reference_id, reason, created_at)
            values (?, ?, 'SALE', -1, ?, 'Migration fixture', now())
            """, UUID.randomUUID(), product, paidOrder);

        var tables = List.of("orders.orders", "orders.order_items", "sale.sale_items", "payment.payments",
            "customer.account_ledger", "inventory.inventory_balances", "inventory.stock_movements");
        var before = tables.stream().map(table -> jdbc.queryForList("select * from " + table + " order by 1")).toList();
        String customerFields = "select id, business_name, tax_id, seller_id, balance, status, created_at, price_list_id, customer_number, last_order_number, email, phone, address, zone, kind, reseller_id, reseller_kind from customer.customers order by id";
        var customersBefore = jdbc.queryForList(customerFields);
        String saleFields = "select id, order_id, customer_id, account_customer_id, status, total, paid, created_at from sale.sales order by id";
        var salesBefore = jdbc.queryForList(saleFields);
        Flyway.configure().dataSource(source).load().migrate();

        assertThat(tables.stream().map(table -> jdbc.queryForList("select * from " + table + " order by 1")).toList()).isEqualTo(before);
        assertThat(jdbc.queryForList(customerFields)).isEqualTo(customersBefore);
        assertThat(jdbc.queryForList(saleFields)).isEqualTo(salesBefore);
        assertThat(jdbc.queryForObject("select count(*) from sale.sales s join orders.orders o on o.id = s.order_id where s.sale_number <> o.order_number", Integer.class)).isZero();
        assertThat(jdbc.queryForList("select legacy_sale_number from sale.sales order by sale_number", String.class)).containsExactlyElementsOf(oldNumbers);
        var reads = new ReadQueryService(jdbc, TestCurrentUserAccess.admin(jdbc));
        assertThat(reads.sales(0, 20, "V-00000012").content()).extracting(row -> row.get("number")).containsExactly("000100003");
        assertThat(reads.sales(0, 20, "000100003").content()).extracting(row -> row.get("number")).containsExactly("000100003");
        assertThatThrownBy(() -> jdbc.update("update sale.sales set sale_number = '000100003' where id = ?", paidSale))
            .isInstanceOf(DataAccessException.class);
    }
}
