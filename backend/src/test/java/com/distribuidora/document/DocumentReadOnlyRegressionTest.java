package com.distribuidora.document;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.settings.application.BusinessSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.distribuidora.shared.security.TestCurrentUserAccess;

class DocumentReadOnlyRegressionTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        "jdbc:h2:mem:document-read-only;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
    private UUID orderId;
    private UUID productId;
    private UUID customerId;

    @BeforeEach
    void setUpDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE SCHEMA customer");
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute("CREATE SCHEMA sale");
        jdbc.execute("CREATE SCHEMA seller");
        jdbc.execute("CREATE SCHEMA payment");
        jdbc.execute("CREATE SCHEMA inventory");
        jdbc.execute("CREATE SCHEMA app");
        jdbc.execute("CREATE TABLE app.business_settings (id SMALLINT PRIMARY KEY, business_name VARCHAR(120), business_address VARCHAR(240), business_phone VARCHAR(60), business_logo BYTEA, updated_by UUID, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO app.business_settings(id, business_name, business_address, business_phone) VALUES (1, 'Comercio', '', '')");
        jdbc.execute("CREATE TABLE customer.customers (id UUID PRIMARY KEY, business_name VARCHAR(120), balance NUMERIC(19,4))");
        jdbc.execute("CREATE TABLE seller.seller_profiles (id UUID PRIMARY KEY, display_name VARCHAR(120))");
        jdbc.execute("CREATE TABLE orders.orders (id UUID PRIMARY KEY, customer_id UUID, seller_id UUID, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE sale.sales (id UUID PRIMARY KEY, order_id UUID, customer_id UUID, sale_number VARCHAR(40), created_at TIMESTAMP WITH TIME ZONE, total NUMERIC(19,4), paid NUMERIC(19,4), status VARCHAR(20), order_discount_percent NUMERIC(19,4))");
        jdbc.execute("CREATE TABLE sale.sale_items (id UUID PRIMARY KEY, sale_id UUID, product_name VARCHAR(120), quantity NUMERIC(19,4), unit_price NUMERIC(19,4), line_discount_percent NUMERIC(19,4), line_total NUMERIC(19,4))");
        jdbc.execute("CREATE TABLE payment.payments (id UUID PRIMARY KEY, sale_id UUID, method VARCHAR(30), amount NUMERIC(19,4), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE inventory.inventory_balances (product_id UUID PRIMARY KEY, quantity NUMERIC(19,4))");
        jdbc.execute("CREATE TABLE inventory.stock_movements (id UUID PRIMARY KEY, product_id UUID, movement_type VARCHAR(30), quantity NUMERIC(19,4))");
        jdbc.execute("CREATE TABLE customer.account_ledger (id UUID PRIMARY KEY, customer_id UUID, entry_type VARCHAR(20), amount NUMERIC(19,4))");

        orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        jdbc.update("INSERT INTO customer.customers VALUES (?, ?, ?)", customerId, "Customer", new BigDecimal("12.50"));
        jdbc.update("INSERT INTO seller.seller_profiles VALUES (?, ?)", sellerId, "Seller");
        jdbc.update("INSERT INTO orders.orders VALUES (?, ?, ?, ?)", orderId, customerId, sellerId, "CONFIRMED");
        jdbc.update("INSERT INTO sale.sales VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)", saleId, orderId, customerId, "SAL-READ-ONLY", Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 10, 15)), new BigDecimal("30.00"), new BigDecimal("20.00"), "CONFIRMED");
        jdbc.update("INSERT INTO sale.sale_items VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), saleId, "Product", new BigDecimal("2.00"), new BigDecimal("15.00"), BigDecimal.ZERO, new BigDecimal("30.00"));
        jdbc.update("INSERT INTO payment.payments VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), saleId, "CASH", new BigDecimal("20.00"), Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 10, 16)));
        jdbc.update("INSERT INTO inventory.inventory_balances VALUES (?, ?)", productId, new BigDecimal("8.00"));
        jdbc.update("INSERT INTO inventory.stock_movements VALUES (?, ?, ?, ?)", UUID.randomUUID(), productId, "SALE", new BigDecimal("2.00"));
        jdbc.update("INSERT INTO customer.account_ledger VALUES (?, ?, ?, ?)", UUID.randomUUID(), customerId, "DEBIT", new BigDecimal("10.00"));
    }

    @Test
    void generatingDocumentDoesNotChangePersistedBusinessState() {
        Map<String, Object> before = snapshot();

        SaleDocumentModel document = new SaleDocumentService(jdbc, TestCurrentUserAccess.admin(jdbc)).load(orderId);
        byte[] pdf = new OpenPdfA4Renderer().render(document);

        assertThat(pdf).startsWith("%PDF".getBytes());
        assertThat(pdf).isNotEmpty();
        assertThat(new SaleDocumentService(jdbc, TestCurrentUserAccess.admin(jdbc)).load(orderId).saleNumber()).isEqualTo("SAL-READ-ONLY");
        assertThat(snapshot()).containsExactlyInAnyOrderEntriesOf(before);
    }

    @Test
    void usesTheSavedLogoInBothFormatsAndOmitsItAfterRemoval() throws IOException {
        var settings = new BusinessSettingsService(jdbc, mock(AuditService.class));
        var documents = new SaleDocumentService(jdbc, TestCurrentUserAccess.admin(jdbc));
        var logo = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        logo.setRGB(10, 10, 0xff34745d);
        var source = new ByteArrayOutputStream();
        ImageIO.write(logo, "png", source);

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(UUID.randomUUID().toString(), null,
            List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        SecurityContextHolder.setContext(context);
        try {
            assertThat(settings.businessLogo()).isNull();
            byte[] saved = settings.updateBusinessLogo(Base64.getEncoder().encodeToString(source.toByteArray()));
            assertThat(settings.businessLogo()).containsExactly(saved);
            var model = documents.load(orderId);
            assertThat(model.businessLogo()).containsExactly(saved);
            assertThat(hasImage(new OpenPdfA4Renderer().render(model))).isTrue();
            assertThat(hasImage(new OpenPdfTicketRenderer().render(model))).isTrue();

            settings.removeBusinessLogo();
            var withoutLogo = documents.load(orderId);
            assertThat(withoutLogo.businessLogo()).isNull();
            assertThat(hasImage(new OpenPdfA4Renderer().render(withoutLogo))).isFalse();
            assertThat(hasImage(new OpenPdfTicketRenderer().render(withoutLogo))).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean hasImage(byte[] pdf) throws IOException {
        try (var reader = new PdfReader(pdf)) {
            var objects = reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT);
            return objects != null && objects.getKeys().stream().anyMatch(key -> {
                var stream = objects.getAsStream(key);
                return stream != null && PdfName.IMAGE.equals(stream.getAsName(PdfName.SUBTYPE));
            });
        }
    }

    private Map<String, Object> snapshot() {
        return jdbc.queryForMap("""
            SELECT o.status AS order_status,
                   s.status AS sale_status,
                   ib.quantity AS stock_quantity,
                   (SELECT count(*) FROM inventory.stock_movements) AS stock_movements,
                   (SELECT count(*) FROM payment.payments) AS payments,
                   (SELECT sum(amount) FROM payment.payments) AS payment_total,
                   (SELECT count(*) FROM customer.account_ledger) AS ledger_entries,
                   (SELECT sum(amount) FROM customer.account_ledger) AS ledger_total,
                   c.balance AS customer_balance
              FROM orders.orders o
              JOIN sale.sales s ON s.order_id = o.id
              JOIN customer.customers c ON c.id = o.customer_id
              JOIN inventory.inventory_balances ib ON ib.product_id = ?
             WHERE o.id = ?
            """, productId, orderId);
    }
}
