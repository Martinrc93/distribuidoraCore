-- Extensive 30-day test dataset for Distribuidora (PostgreSQL 16)
-- Deterministic, atomic, respects every FK, CHECK, sequence, ledger, stock and commission invariant.
-- Preserves identity roles/permissions and the existing primary admin user (admin@demo).

BEGIN;

SET LOCAL TIME ZONE 'UTC';

DO $$
DECLARE
    v_tz TEXT := 'America/Argentina/Buenos_Aires';
    v_today DATE := (CURRENT_TIMESTAMP AT TIME ZONE 'America/Argentina/Buenos_Aires')::date;
    v_now TIMESTAMPTZ := CURRENT_TIMESTAMP;

    v_admin_id UUID;
    v_admin_hash TEXT;
    v_admin2_id UUID;
    v_admin_role_id UUID;
    v_seller_role_id UUID;

    v_list_general UUID := '00000000-0000-0000-0000-000000000001'::uuid;
    v_list_mayorista UUID;
    v_list_distribuidor UUID;
    v_list_tarjeta UUID;
    v_list_inactive UUID;

    v_seller_ids UUID[] := ARRAY[]::UUID[];
    v_seller_user_ids UUID[] := ARRAY[]::UUID[];
    v_zone_names TEXT[];
    v_cat_ids UUID[] := ARRAY[]::UUID[];
    v_cat_names TEXT[];
    v_brand_ids UUID[] := ARRAY[]::UUID[];
    v_brand_names TEXT[];
    v_supplier_ids UUID[] := ARRAY[]::UUID[];
    v_product_ids UUID[] := ARRAY[]::UUID[];
    v_reseller_ids UUID[] := ARRAY[]::UUID[];
    v_customer_ids UUID[] := ARRAY[]::UUID[];
    v_plan_ids UUID[] := ARRAY[]::UUID[];

    v_uid UUID;
    v_sp_id UUID;
    v_cid UUID;
    v_bid UUID;
    v_pid UUID;
    v_sid UUID;
    v_sup_id UUID;
    v_order_id UUID;
    v_sale_id UUID;
    v_return_id UUID;
    v_settlement_id UUID;
    v_price_list_id UUID;
    v_price_list_code TEXT;

    i INT;
    j INT;
    k INT;
    v_day_offset INT;
    v_order_date DATE;
    v_order_ts TIMESTAMPTZ;
    v_prep_ts TIMESTAMPTZ;
    v_deliv_ts TIMESTAMPTZ;
    v_cancel_ts TIMESTAMPTZ;
    v_pay_ts TIMESTAMPTZ;

    v_base_cost NUMERIC(19,4);
    v_price_old NUMERIC(19,4);
    v_price_cur NUMERIC(19,4);
    v_unit_price NUMERIC(19,4);
    v_qty NUMERIC(19,4);
    v_line_disc_pct NUMERIC(19,4);
    v_line_base NUMERIC(19,4);
    v_line_disc NUMERIC(19,4);
    v_line_total NUMERIC(19,4);
    v_subtotal NUMERIC(19,4);
    v_disc_subtotal NUMERIC(19,4);
    v_total_line_disc NUMERIC(19,4);
    v_order_disc_pct NUMERIC(19,4);
    v_order_disc NUMERIC(19,4);
    v_total NUMERIC(19,4);
    v_paid_upfront NUMERIC(19,4);
    v_paid_later NUMERIC(19,4);
    v_paid_total NUMERIC(19,4);
    v_account_debit NUMERIC(19,4);
    v_prev_bal NUMERIC(19,4);
    v_cur_bal NUMERIC(19,4);
    v_proj_bal NUMERIC(19,4);
    v_credit_limit NUMERIC(19,4) := 1500000.0000;
    v_credit_exceeded BOOLEAN;

    v_cust_rec RECORD;
    v_prod_rec RECORD;
    v_sale_rec RECORD;
    v_line_rec RECORD;
    v_ben_rec RECORD;

    v_order_status TEXT;
    v_fulfill_status TEXT;
    v_order_number TEXT;
    v_sale_number TEXT;
    v_created_by_admin BOOLEAN;
    v_created_by_user UUID;
    v_reviewed_at TIMESTAMPTZ;
    v_reviewed_by UUID;
    v_prep_by UUID;
    v_attempt_by UUID;
    v_pay_method TEXT;
    v_pay_ref TEXT;
    v_line_count INT;
    v_used_prods UUID[];
    v_pick_idx INT;
    v_r DOUBLE PRECISION;
    v_orders_for_day INT;
    v_dow INT;
    v_order_seq INT := 0;
    v_po_status TEXT;
    v_po_total NUMERIC(19,4);
    v_po_id UUID;
    v_po_number TEXT;
    v_po_date DATE;
    v_po_ts TIMESTAMPTZ;
    v_po_cost NUMERIC(19,4);
    v_po_qty NUMERIC(19,4);
    v_po_line_total NUMERIC(19,4);
    v_ret_qty NUMERIC(19,4);
    v_ret_ts TIMESTAMPTZ;
    v_ret_reason TEXT;
    v_exp_type TEXT;
    v_exp_amt NUMERIC(14,2);
    v_exp_obs TEXT;
    v_exp_ts TIMESTAMPTZ;
    v_settle_cutoff TIMESTAMPTZ;
    v_settle_paid_at TIMESTAMPTZ;
    v_target_stock NUMERIC(19,4);
    v_net_moves NUMERIC(19,4);
    v_initial_stock NUMERIC(19,4);
    v_adj_delta NUMERIC(19,4);
    v_shrink_qty NUMERIC(19,4);
    v_sold_30d NUMERIC(19,4);

    v_seller_names TEXT[] := ARRAY[
        'Martín Gómez',
        'Lucía Fernández',
        'Diego Morales',
        'Sofía Ramírez',
        'Lucas Pereyra',
        'Valentina Castro',
        'Nicolás Herrera',
        'Camila Torres'
    ];
    v_zone_list TEXT[] := ARRAY[
        'Centro', 'Zona Norte', 'Zona Sur', 'Zona Oeste',
        'Microcentro', 'Palermo', 'Belgrano', 'Caballito',
        'Avellaneda', 'Quilmes', 'San Isidro', 'Vicente López'
    ];
    v_brand_list TEXT[] := ARRAY[
        'Coca-Cola', 'Quilmes', 'Arcor', 'Molinos', 'Marolio', 'La Serenísima',
        'Unilever', 'Ala', 'Cif', 'Magistral', 'Bagley', 'Terrabusi',
        'Villa del Sur', 'Cepita', 'Patagonia', 'Brahma', 'Ledesma', 'Marca Discontinuada'
    ];
    v_brand_codes TEXT[] := ARRAY[
        'COCA', 'QUILMES', 'ARCOR', 'MOLINOS', 'MAROLIO', 'SERENISIMA',
        'UNILEVER', 'ALA', 'CIF', 'MAGISTRAL', 'BAGLEY', 'TERRABUSI',
        'VILLADELSUR', 'CEPITA', 'PATAGONIA', 'BRAHMA', 'LEDESMA', 'DISCONT'
    ];
    v_cat_list TEXT[] := ARRAY[
        'Bebidas sin Alcohol', 'Cervezas y Vinos', 'Almacén Seco', 'Galletitas y Golosinas',
        'Lácteos y Frescos', 'Limpieza del Hogar', 'Perfumería e Higiene', 'Temporada Anterior'
    ];
    v_cat_codes TEXT[] := ARRAY[
        'BEBIDAS', 'CERVEZAS', 'ALMACEN', 'GOLOSINAS',
        'LACTEOS', 'LIMPIEZA', 'PERFUMERIA', 'TEMP_ANT'
    ];
    v_supplier_names TEXT[] := ARRAY[
        'Distribuidora Bebidas del Sur S.A.',
        'Mayorista Alimentos Central S.R.L.',
        'Molinos del Plata Comercial',
        'Química Industrial Norte S.A.',
        'Lácteos Pampeanos Distribución',
        'Cervecería y Maltería Regional',
        'Logística Golosinas Argentinas',
        'Perfumería Integral Mayorista',
        'Aguas y Jugos del Litoral S.A.',
        'Abastecedora Comercial Oeste',
        'Proveedora Gastronómica Metro',
        'Consumo Masivo Río de la Plata',
        'Papelera y Limpieza Industrial S.R.L.',
        'Bodegas y Bebidas Federales',
        'Alimentos Secos San Martín',
        'Importadora y Distribución Delta'
    ];
    v_reseller_names TEXT[] := ARRAY[
        'Revendedor Mayorista Delta',
        'Distribuidora Expreso Sur',
        'Comercializadora del Oeste S.R.L.',
        'Red Comercial Zona Norte',
        'Mayorista Express Avellaneda',
        'Abastecedora Quilmes Centro',
        'Logística Minorista San Isidro',
        'Grupo Comercial Belgrano',
        'Distribuciones Rápidas Palermo',
        'Revendedor Integral Caballito',
        'Punto Mayorista Vicente López',
        'Enlace Comercial Microcentro'
    ];
    v_cust_prefixes TEXT[] := ARRAY[
        'Autoservicio', 'Supermercado', 'Almacén', 'Kiosco', 'Despensa',
        'Vinoteca', 'Bar y Café', 'Mercado', 'Minimercado', 'Dietética',
        'Polirrubro', 'Limpieza Total', 'Parrilla', 'Panadería', 'Rotisería'
    ];
    v_cust_suffixes TEXT[] := ARRAY[
        'El Progreso', 'San José', 'La Esquina', 'Los Amigos', 'Don Pedro',
        'Santa Rita', 'El Trebol', 'La Familia', 'Nueva Era', 'Del Parque',
        'San Martín', 'El Sol', 'La Estrella', 'Buen Precio', 'Los Hermanos',
        'Rivadavia', 'Mitre', 'Sarmiento', 'Belgrano', 'Las Flores',
        'El Ceibo', 'La Plaza', 'Avenida', 'El Puente', 'San Cayetano',
        'Villa Urquiza', 'Libertador', 'Pueyrredón', 'Independencia', 'Corrientes'
    ];
    v_streets TEXT[] := ARRAY[
        'Av. Rivadavia', 'Av. Corrientes', 'Av. Santa Fe', 'Av. Cabildo',
        'Av. Mitre', 'Av. Hipólito Yrigoyen', 'Av. Maipú', 'Av. San Martín',
        'Av. Belgrano', 'Av. Boedo', 'Av. Álvarez Thomas', 'Av. Independencia'
    ];
    v_presentations TEXT[] := ARRAY[
        'Pack x 6', 'Caja x 12', 'Bulto x 24', 'Fardo x 10', 'Bidón 5L',
        'Pack x 8', 'Caja x 20', 'Unidad', 'Pack x 12', 'Display x 18'
    ];
    v_fail_reasons TEXT[] := ARRAY[
        'Local cerrado al momento de la entrega',
        'Sin recepción en depósito, reprogramar',
        'Responsable de compras ausente',
        'Calle cortada por obra, sin acceso de camioneta',
        'Cliente solicitó pasar al día siguiente'
    ];
    v_return_reasons TEXT[] := ARRAY[
        'Mercadería dañada durante el transporte',
        'Envase abollado o con pérdida',
        'Error de armado en depósito',
        'Lote con vencimiento corto rechazado por cliente',
        'Cliente solicitó cambio de presentación'
    ];
    v_shrink_reasons TEXT[] := ARRAY[
        'Rotura en depósito durante estiba',
        'Vencimiento de lote en estantería',
        'Envase dañado en descarga de proveedor',
        'Pérdida por filtración en pack',
        'Diferencia detectada en conteo cíclico'
    ];
    v_adj_reasons TEXT[] := ARRAY[
        'Ajuste por inventario físico semanal',
        'Corrección de recuento en pasillo A',
        'Ingreso de bulto recuperado en depósito',
        'Regularización de stock tras control de lote'
    ];
    v_fuel_obs TEXT[] := ARRAY[
        'Combustible reparto Zona Norte - YPF',
        'Carga gasoil camioneta reparto Sur',
        'Combustible flete semanal Zona Oeste',
        'Nafta movilidad vendedores centro'
    ];
    v_service_obs TEXT[] := ARRAY[
        'Electricidad cámara fría y depósito',
        'Servicio de internet y telefonía oficina',
        'Mantenimiento preventivo autoelevador',
        'Alquiler mensual galpón de logística',
        'Seguro flota de reparto mensual'
    ];
    v_petty_obs TEXT[] := ARRAY[
        'Viáticos choferes y peajes autopista',
        'Caja chica insumos de oficina y café',
        'Peajes acceso norte y Ricchieri',
        'Artículos de limpieza para depósito'
    ];
    v_other_obs TEXT[] := ARRAY[
        'Film stretch, cinta y etiquetas de despacho',
        'Reparación zorra hidráulica depósito',
        'Compra de pallets reforzados',
        'Impresión de talonarios y remitos'
    ];
    -- Product catalog templates per category (7 active categories x 30 items = 210 active + 10 inactive)
    v_prod_bebidas TEXT[] := ARRAY[
        'Gaseosa Cola 2.25L', 'Gaseosa Cola Sin Azúcar 2.25L', 'Gaseosa Cola 500ml', 'Gaseosa Lima Limón 2.25L',
        'Gaseosa Naranja 2.25L', 'Gaseosa Pomelo 1.5L', 'Agua Sin Gas 2L', 'Agua Sin Gas 500ml',
        'Agua Con Gas 1.5L', 'Agua Saborizada Manzana 1.5L', 'Agua Saborizada Naranja 1.5L', 'Agua Saborizada Pomelo 1.5L',
        'Jugo Naranja 1L', 'Jugo Manzana 1L', 'Jugo Multifruta 1L', 'Jugo Durazno 200ml',
        'Bebida Isotónica Limón 500ml', 'Bebida Isotónica Frutas 500ml', 'Energizante Clásico 473ml', 'Energizante Sin Azúcar 473ml',
        'Agua Tónica 1.5L', 'Soda Sifón Descartable 2L', 'Gaseosa Cola 1.5L', 'Gaseosa Cola Lata 354ml',
        'Gaseosa Lima Lata 354ml', 'Agua Mineral Bidón 6L', 'Jugo en Polvo Naranja', 'Jugo en Polvo Limón',
        'Té Helado Limón 1.5L', 'Amargo Serrano 1.5L'
    ];
    v_prod_cervezas TEXT[] := ARRAY[
        'Cerveza Rubia Lata 473ml', 'Cerveza Rubia Botella 1L', 'Cerveza Roja Lata 473ml', 'Cerveza Negra Lata 473ml',
        'Cerveza IPA Lata 473ml', 'Cerveza Amber Lager 730ml', 'Cerveza Bohemian Pilsener 473ml', 'Cerveza Sin Alcohol 473ml',
        'Cerveza Suave Lata 473ml', 'Cerveza Chopp Lata 710ml', 'Vino Malbec Clásico 750ml', 'Vino Cabernet Sauvignon 750ml',
        'Vino Blanco Torrontés 750ml', 'Vino Tinto Tetra 1L', 'Vino Blanco Tetra 1L', 'Vino Rosado Dulce 750ml',
        'Espumante Extra Brut 750ml', 'Espumante Dulce 750ml', 'Fernet Clásico 750ml', 'Aperitivo Herbal 750ml',
        'Aperitivo Americano 950ml', 'Vermouth Rosso 750ml', 'Sidra Clásica 720ml', 'Gin Seco 750ml',
        'Vodka Clásico 700ml', 'Cerveza Honey Lata 473ml', 'Cerveza Weisse 473ml', 'Vino Reserva Malbec 750ml',
        'Fernet 450ml', 'Cerveza Rubia Porrón 340ml'
    ];
    v_prod_almacen TEXT[] := ARRAY[
        'Arroz Largo Fino 1kg', 'Arroz Doble Carolina 1kg', 'Fideos Tallarines 500g', 'Fideos Tirabuzón 500g',
        'Fideos Mostacholes 500g', 'Fideos Coditos 500g', 'Aceite Girasol 900ml', 'Aceite Girasol 1.5L',
        'Aceite Mezcla 900ml', 'Aceite Oliva Extra Virgen 500ml', 'Harina 000 1kg', 'Harina 0000 1kg',
        'Harina Leudante 1kg', 'Azúcar Común Tipo A 1kg', 'Yerba Mate Tradicional 1kg', 'Yerba Mate Suave 500g',
        'Puré de Tomate 520g', 'Tomate Perita Lata 400g', 'Arvejas en Lata 300g', 'Choclo Cremoso Lata 300g',
        'Lentejas Secas 400g', 'Polenta Instantánea 500g', 'Sal Fina Paquete 500g', 'Mayonesa Doypack 500g',
        'Ketchup Doypack 250g', 'Mostaza Doypack 250g', 'Vinagre de Alcohol 1L', 'Café Molido Torrado 500g',
        'Té en Saquitos x 50', 'Mate Cocido en Saquitos x 50'
    ];
    v_prod_golosinas TEXT[] := ARRAY[
        'Galletitas de Agua 300g', 'Galletitas Salvado 300g', 'Galletitas Dulces Vainilla 170g', 'Galletitas Rellenas Chocolate 118g',
        'Galletitas Surtidas 400g', 'Galletitas Pepas Membrillo 200g', 'Galletitas Chips Chocolate 150g', 'Alfajor Chocolate Negro',
        'Alfajor Blanco Dulce de Leche', 'Alfajor Triple Chocolate', 'Turrón de Maní 25g', 'Caramelos Masticables Frutales 800g',
        'Chupetines Surtidos Bolsa x 50', 'Chocolate con Leche 100g', 'Chocolate Semiamargo 100g', 'Obleas Rellenas Chocolate',
        'Barra de Cereal Frutilla', 'Barra de Cereal Chocolate', 'Budín Vainilla 220g', 'Budín Marmolado 220g',
        'Magdalenas Vainilla 200g', 'Papas Fritas Clásicas 150g', 'Palitos Salados 200g', 'Maní Salado Pelado 120g',
        'Chizitos Queso 150g', 'Gomitas Frutales 500g', 'Pastillas Menta Tubo', 'Chicles Menta Caja x 20',
        'Galletitas de Arroz Sin Sal', 'Tostadas Clásicas 200g'
    ];
    v_prod_lacteos TEXT[] := ARRAY[
        'Leche Entera Larga Vida 1L', 'Leche Descremada Larga Vida 1L', 'Leche Deslactosada 1L', 'Leche Chocolatada 1L',
        'Leche Chocolatada 200ml', 'Yogur Bebible Vainilla 1L', 'Yogur Bebible Frutilla 1L', 'Yogur Firme Pote 190g',
        'Dulce de Leche Clásico 400g', 'Dulce de Leche Repostero 400g', 'Manteca Calidad Extra 200g', 'Margarina Untable 500g',
        'Crema de Leche 200ml', 'Crema de Leche 500ml', 'Queso Crema Clásico 300g', 'Queso Crema Light 300g',
        'Queso Cremoso Horma Fracc 1kg', 'Queso Barra Tybo 1kg', 'Queso Rallado Sobre 120g', 'Postre Vainilla Pote 120g',
        'Postre Chocolate Pote 120g', 'Flan Casero Pote 120g', 'Ricota Entera 500g', 'Tapas de Empanadas Hojaldre',
        'Tapas de Empanadas Criollas', 'Tapas de Pascualina Hojaldre', 'Ravioles Ricota y Espinaca 500g', 'Ñoquis de Papa 500g',
        'Mermelada Durazno Frasco 454g', 'Mermelada Frutilla Frasco 454g'
    ];
    v_prod_limpieza TEXT[] := ARRAY[
        'Jabón Líquido Ropa 3L', 'Jabón en Polvo Baja Espuma 800g', 'Suavizante Ropa Clásico 900ml', 'Suavizante Ropa Concentrado 500ml',
        'Detergente Limón 750ml', 'Detergente Ultra Concentrado 500ml', 'Lavandina Clásica 2L', 'Lavandina en Gel 700ml',
        'Limpiador Pisos Lavanda 900ml', 'Limpiador Pisos Pino 1.8L', 'Limpiador Cremoso Multiuso 750ml', 'Desengrasante Cocina Gatillo 500ml',
        'Limpiavidrios Gatillo 500ml', 'Desodorante de Ambiente Aerosol', 'Insecticida Mata Moscas 360ml', 'Bolsas de Residuos 45x60 x 30',
        'Bolsas de Consorcio 80x110 x 10', 'Esponja Doble Uso', 'Trapo de Piso Gris Reforzado', 'Rejilla Americana Algodón',
        'Papel Higiénico Hoja Simple x 4', 'Papel Higiénico Doble Hoja x 4', 'Rollo de Cocina x 3', 'Servilletas de Papel x 100',
        'Lustramuebles Aerosol 360ml', 'Cera Líquida Autobrillo 900ml', 'Quitamanchas Pre-Lavado 500ml', 'Pastilla Inodoro Canasta',
        'Escobillón Plástico con Cabo', 'Guantes Látex Multiuso Talle M'
    ];
    v_prod_perfumeria TEXT[] := ARRAY[
        'Shampoo Hidratación 400ml', 'Acondicionador Hidratación 400ml', 'Shampoo Anticaspa 400ml', 'Jabón de Tocador Cremoso x 3',
        'Jabón Líquido Manos Repuesto 250ml', 'Desodorante Aerosol Masculino 150ml', 'Desodorante Aerosol Femenino 150ml', 'Desodorante Roll-On 50ml',
        'Pasta Dental Triple Acción 90g', 'Pasta Dental Blanqueadora 90g', 'Cepillo Dental Suave', 'Enjuague Bucal Menta 500ml',
        'Algodón Hidrófilo 100g', 'Hisopos Caja x 100', 'Pañuelos Descartables x 6', 'Toallas Femeninas con Alas x 16',
        'Protectores Diarios x 40', 'Pañales Descartables Talle G x 36', 'Pañales Descartables Talle XG x 32', 'Toallitas Húmedas Bebé x 50',
        'Crema Corporal Hidratante 400ml', 'Crema de Manos 75ml', 'Alcohol Etílico 96% 500ml', 'Alcohol en Gel 250ml',
        'Agua Oxigenada 10 Vol 250ml', 'Máquina de Afeitar Descartable x 2', 'Espuma de Afeitar 200ml', 'Talco Corporal Clásico 200g',
        'Protector Solar FPS 50 200ml', 'Repelente de Insectos Aerosol 170ml'
    ];
    v_prod_inactivos TEXT[] := ARRAY[
        'Pan Dulce con Frutas 500g (Temp. Ant.)', 'Sidra Etiqueta Dorada 750ml (Temp. Ant.)',
        'Budín Navideño 250g (Temp. Ant.)', 'Garrapiñada de Maní 100g (Temp. Ant.)',
        'Turrón Blando de Almendras (Temp. Ant.)', 'Confites de Maní 120g (Temp. Ant.)',
        'Gaseosa Edición Especial 2L (Discont.)', 'Cerveza Edición Limitada 473ml (Discont.)',
        'Limpiador Aroma Invierno 900ml (Discont.)', 'Pack Promo Verano Mix (Discont.)'
    ];
    v_prod_desc TEXT;
    v_cat_idx INT;
    v_brand_idx INT;
BEGIN
    PERFORM setseed(0.424242);

    -- 1. Preserve primary admin user & role IDs
    SELECT id, password_hash INTO v_admin_id, v_admin_hash
    FROM identity.users
    WHERE lower(email) = 'admin@demo'
    LIMIT 1;

    IF v_admin_id IS NULL THEN
        SELECT id, password_hash INTO v_admin_id, v_admin_hash
        FROM identity.users
        ORDER BY created_at
        LIMIT 1;
    END IF;

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'No se encontró un usuario administrador base para preservar su hash de contraseña.';
    END IF;

    SELECT id INTO v_admin_role_id FROM identity.roles WHERE code = 'ADMIN';
    SELECT id INTO v_seller_role_id FROM identity.roles WHERE code = 'SELLER';

    -- 2. Clean business tables (keep identity.roles, permissions, role_permissions and primary admin)
    TRUNCATE TABLE
        audit.audit_events,
        notification.delivery_requests,
        notification.outbox_events,
        expense.expenses,
        sale.commission_entries,
        sale.commission_settlements,
        app.reseller_commission_plans,
        app.seller_commission_plans,
        app.commission_rules,
        app.commission_plans,
        sale.return_items,
        sale.returns,
        payment.payments,
        customer.account_ledger,
        sale.sale_items,
        sale.sales,
        orders.delivery_attempts,
        orders.order_items,
        orders.orders,
        purchasing.supplier_order_items,
        purchasing.supplier_orders,
        inventory.stock_movements,
        inventory.inventory_balances,
        catalog.product_suppliers,
        catalog.price_list_follows,
        catalog.product_price_history,
        catalog.product_prices,
        catalog.products,
        catalog.brands,
        catalog.categories,
        customer.customers,
        customer.zones,
        seller.seller_profiles,
        supplier.suppliers,
        demo.seed_runs,
        identity.refresh_tokens,
        identity.user_activation_tokens
    CASCADE;

    DELETE FROM catalog.price_lists WHERE id <> v_list_general;
    DELETE FROM identity.user_roles WHERE user_id <> v_admin_id;
    DELETE FROM identity.users WHERE id <> v_admin_id;

    -- Reset sequences cleanly
    PERFORM setval('customer.customer_number_seq', 1, false);
    PERFORM setval('purchasing.supplier_order_number_seq', 1, false);

    -- Ensure primary admin has ADMIN role and is ACTIVE
    UPDATE identity.users
    SET status = 'ACTIVE', failed_login_attempts = 0, locked_until = NULL, updated_at = v_now
    WHERE id = v_admin_id;
    INSERT INTO identity.user_roles (user_id, role_id)
    VALUES (v_admin_id, v_admin_role_id)
    ON CONFLICT DO NOTHING;

    -- 3. Business settings (global credit limit = ARS 1,500,000)
    INSERT INTO app.business_settings (id, credit_limit, updated_by, updated_at)
    VALUES (1, v_credit_limit, v_admin_id, v_now - INTERVAL '32 days')
    ON CONFLICT (id) DO UPDATE
        SET credit_limit = EXCLUDED.credit_limit,
            updated_by = EXCLUDED.updated_by,
            updated_at = EXCLUDED.updated_at;

    -- 4. Second Admin + 8 Sellers (all share the same password hash as admin@demo -> Prueba123)
    v_admin2_id := gen_random_uuid();
    INSERT INTO identity.users (id, email, password_hash, status, failed_login_attempts, created_at, updated_at, version)
    VALUES (v_admin2_id, 'gerencia@demo', v_admin_hash, 'ACTIVE', 0, v_now - INTERVAL '60 days', v_now - INTERVAL '60 days', 0);
    INSERT INTO identity.user_roles (user_id, role_id) VALUES (v_admin2_id, v_admin_role_id);

    FOR i IN 1..array_length(v_seller_names, 1) LOOP
        v_uid := gen_random_uuid();
        v_sp_id := gen_random_uuid();
        INSERT INTO identity.users (id, email, password_hash, status, failed_login_attempts, created_at, updated_at, version)
        VALUES (
            v_uid,
            'vendedor' || i || '@demo',
            v_admin_hash,
            CASE WHEN i = 8 THEN 'DISABLED' ELSE 'ACTIVE' END,
            0,
            v_now - INTERVAL '60 days',
            v_now - INTERVAL '60 days',
            0
        );
        INSERT INTO identity.user_roles (user_id, role_id) VALUES (v_uid, v_seller_role_id);
        INSERT INTO seller.seller_profiles (id, user_id, display_name, status, created_at)
        VALUES (
            v_sp_id,
            v_uid,
            v_seller_names[i],
            CASE WHEN i = 8 THEN 'INACTIVE' ELSE 'ACTIVE' END,
            v_now - INTERVAL '60 days'
        );
        v_seller_ids := array_append(v_seller_ids, v_sp_id);
        v_seller_user_ids := array_append(v_seller_user_ids, v_uid);
    END LOOP;

    -- 5. Zones
    v_zone_names := v_zone_list;
    FOR i IN 1..array_length(v_zone_names, 1) LOOP
        INSERT INTO customer.zones (id, name, created_at, updated_at)
        VALUES (gen_random_uuid(), v_zone_names[i], v_now - INTERVAL '60 days', v_now - INTERVAL '60 days');
    END LOOP;

    -- 6. Categories (7 active + 1 inactive)
    v_cat_names := v_cat_list;
    FOR i IN 1..array_length(v_cat_names, 1) LOOP
        v_cid := gen_random_uuid();
        INSERT INTO catalog.categories (id, name, code, status, created_at)
        VALUES (
            v_cid,
            v_cat_names[i],
            v_cat_codes[i],
            CASE WHEN i = 8 THEN 'INACTIVE' ELSE 'ACTIVE' END,
            v_now - INTERVAL '60 days'
        );
        v_cat_ids := array_append(v_cat_ids, v_cid);
    END LOOP;

    -- 7. Brands (17 active + 1 inactive)
    v_brand_names := v_brand_list;
    FOR i IN 1..array_length(v_brand_names, 1) LOOP
        v_bid := gen_random_uuid();
        INSERT INTO catalog.brands (id, name, code, status, created_at)
        VALUES (
            v_bid,
            v_brand_names[i],
            v_brand_codes[i],
            CASE WHEN i = 18 THEN 'INACTIVE' ELSE 'ACTIVE' END,
            v_now - INTERVAL '60 days'
        );
        v_brand_ids := array_append(v_brand_ids, v_bid);
    END LOOP;

    -- 8. Price lists (4 active + 1 inactive)
    INSERT INTO catalog.price_lists (id, code, name, status, is_default, created_at, updated_at)
    VALUES (v_list_general, 'GENERAL', 'Lista general', 'ACTIVE', true, v_now - INTERVAL '60 days', v_now - INTERVAL '60 days')
    ON CONFLICT (id) DO UPDATE SET name = 'Lista general', status = 'ACTIVE', is_default = true;

    v_list_mayorista := gen_random_uuid();
    v_list_distribuidor := gen_random_uuid();
    v_list_tarjeta := gen_random_uuid();
    v_list_inactive := gen_random_uuid();

    INSERT INTO catalog.price_lists (id, code, name, status, is_default, created_at, updated_at) VALUES
        (v_list_mayorista, 'MAYORISTA', 'Lista mayorista', 'ACTIVE', false, v_now - INTERVAL '55 days', v_now - INTERVAL '55 days'),
        (v_list_distribuidor, 'DISTRIBUIDOR', 'Lista distribuidor', 'ACTIVE', false, v_now - INTERVAL '55 days', v_now - INTERVAL '55 days'),
        (v_list_tarjeta, 'TARJETA', 'Lista tarjeta', 'ACTIVE', false, v_now - INTERVAL '50 days', v_now - INTERVAL '50 days'),
        (v_list_inactive, 'PROMO_INVIERNO', 'Promo invierno', 'INACTIVE', false, v_now - INTERVAL '90 days', v_now - INTERVAL '35 days');

    -- 9. Suppliers (16)
    FOR i IN 1..array_length(v_supplier_names, 1) LOOP
        v_sup_id := gen_random_uuid();
        INSERT INTO supplier.suppliers (id, name, phone, email, address, created_at, updated_at)
        VALUES (
            v_sup_id,
            v_supplier_names[i],
            '+54 11 4' || lpad((210 + i * 37)::text, 3, '0') || '-' || lpad((1000 + i * 211)::text, 4, '0'),
            'ventas' || i || '@proveedor-demo.com.ar',
            v_streets[((i - 1) % array_length(v_streets, 1)) + 1] || ' ' || (1200 + i * 310),
            v_now - INTERVAL '60 days',
            v_now - INTERVAL '60 days'
        );
        v_supplier_ids := array_append(v_supplier_ids, v_sup_id);
    END LOOP;

    -- 10. Products (210 ACTIVE + 10 INACTIVE = 220 products) + Prices + History + Follows + Suppliers
    FOR i IN 1..220 LOOP
        v_pid := gen_random_uuid();
        IF i <= 210 THEN
            v_cat_idx := ((i - 1) / 30) + 1;
            k := ((i - 1) % 30) + 1;
            v_prod_desc := CASE v_cat_idx
                WHEN 1 THEN v_prod_bebidas[k]
                WHEN 2 THEN v_prod_cervezas[k]
                WHEN 3 THEN v_prod_almacen[k]
                WHEN 4 THEN v_prod_golosinas[k]
                WHEN 5 THEN v_prod_lacteos[k]
                WHEN 6 THEN v_prod_limpieza[k]
                ELSE v_prod_perfumeria[k]
            END;
            v_brand_idx := CASE v_cat_idx
                WHEN 1 THEN (ARRAY[1, 13, 14])[((k - 1) % 3) + 1]
                WHEN 2 THEN (ARRAY[2, 15, 16])[((k - 1) % 3) + 1]
                WHEN 3 THEN (ARRAY[4, 5, 17])[((k - 1) % 3) + 1]
                WHEN 4 THEN (ARRAY[3, 11, 12])[((k - 1) % 3) + 1]
                WHEN 5 THEN (ARRAY[6, 4])[((k - 1) % 2) + 1]
                WHEN 6 THEN (ARRAY[7, 8, 9, 10])[((k - 1) % 4) + 1]
                ELSE (ARRAY[7, 10])[((k - 1) % 2) + 1]
            END;
        ELSE
            v_cat_idx := 8;
            v_brand_idx := 18;
            v_prod_desc := v_prod_inactivos[i - 210];
        END IF;

        -- Realistic wholesale pack cost in ARS (2,400 to 38,500)
        v_base_cost := round((2400 + ((i * 733) % 36000) + ((v_cat_idx * 450)))::numeric, 0)::numeric(19,4);

        INSERT INTO catalog.products (
            id, name, description, category, presentation, cost, status, created_at, category_id, brand_id
        ) VALUES (
            v_pid,
            v_brand_names[v_brand_idx] || ' ' || v_prod_desc,
            v_prod_desc,
            v_cat_names[v_cat_idx],
            v_presentations[((i - 1) % array_length(v_presentations, 1)) + 1],
            v_base_cost,
            CASE WHEN i <= 210 THEN 'ACTIVE' ELSE 'INACTIVE' END,
            v_now - INTERVAL '45 days',
            v_cat_ids[v_cat_idx],
            v_brand_ids[v_brand_idx]
        );

        IF i <= 210 THEN
            v_product_ids := array_append(v_product_ids, v_pid);
        END IF;

        -- Historical price (35 days ago) and current price (14 days ago, +6% adjustment)
        -- GENERAL (+38%), MAYORISTA (+28%), DISTRIBUIDOR (+20%), TARJETA (+50%)
        -- Products 1..16 on TARJETA follow GENERAL (so price equals GENERAL)
        v_price_old := round((v_base_cost * 1.30)::numeric, 0)::numeric(19,4);
        v_price_cur := round((v_base_cost * 1.38)::numeric, 0)::numeric(19,4);
        INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
        VALUES (v_list_general, v_pid, v_price_cur, v_now - INTERVAL '35 days', v_now - INTERVAL '14 days');
        INSERT INTO catalog.product_price_history (id, price_list_id, product_id, price, effective_on, created_at, updated_at) VALUES
            (gen_random_uuid(), v_list_general, v_pid, v_price_old, v_today - 35, v_now - INTERVAL '35 days', v_now - INTERVAL '35 days'),
            (gen_random_uuid(), v_list_general, v_pid, v_price_cur, v_today - 14, v_now - INTERVAL '14 days', v_now - INTERVAL '14 days');

        -- Scheduled future price increase (+5%) for first 6 active products
        IF i <= 6 THEN
            INSERT INTO catalog.product_price_history (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            VALUES (gen_random_uuid(), v_list_general, v_pid, round((v_price_cur * 1.05)::numeric, 0), v_today + 4, v_now - INTERVAL '1 day', v_now - INTERVAL '1 day');
        END IF;

        -- MAYORISTA
        v_price_old := round((v_base_cost * 1.21)::numeric, 0)::numeric(19,4);
        v_price_cur := round((v_base_cost * 1.28)::numeric, 0)::numeric(19,4);
        INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
        VALUES (v_list_mayorista, v_pid, v_price_cur, v_now - INTERVAL '35 days', v_now - INTERVAL '14 days');
        INSERT INTO catalog.product_price_history (id, price_list_id, product_id, price, effective_on, created_at, updated_at) VALUES
            (gen_random_uuid(), v_list_mayorista, v_pid, v_price_old, v_today - 35, v_now - INTERVAL '35 days', v_now - INTERVAL '35 days'),
            (gen_random_uuid(), v_list_mayorista, v_pid, v_price_cur, v_today - 14, v_now - INTERVAL '14 days', v_now - INTERVAL '14 days');

        -- DISTRIBUIDOR
        v_price_old := round((v_base_cost * 1.14)::numeric, 0)::numeric(19,4);
        v_price_cur := round((v_base_cost * 1.20)::numeric, 0)::numeric(19,4);
        INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
        VALUES (v_list_distribuidor, v_pid, v_price_cur, v_now - INTERVAL '35 days', v_now - INTERVAL '14 days');
        INSERT INTO catalog.product_price_history (id, price_list_id, product_id, price, effective_on, created_at, updated_at) VALUES
            (gen_random_uuid(), v_list_distribuidor, v_pid, v_price_old, v_today - 35, v_now - INTERVAL '35 days', v_now - INTERVAL '35 days'),
            (gen_random_uuid(), v_list_distribuidor, v_pid, v_price_cur, v_today - 14, v_now - INTERVAL '14 days', v_now - INTERVAL '14 days');

        -- TARJETA (first 16 products follow GENERAL)
        IF i <= 16 THEN
            v_price_old := round((v_base_cost * 1.30)::numeric, 0)::numeric(19,4);
            v_price_cur := round((v_base_cost * 1.38)::numeric, 0)::numeric(19,4);
            INSERT INTO catalog.price_list_follows (
                price_list_id, product_id, review_status, warning_acknowledged, created_at, updated_at, reviewed_by, reviewed_at
            ) VALUES (
                v_list_tarjeta, v_pid,
                CASE WHEN i <= 8 THEN 'PENDING' ELSE 'CONFIRMED' END,
                (i > 8),
                v_now - INTERVAL '35 days',
                v_now - INTERVAL '14 days',
                CASE WHEN i > 8 THEN v_admin_id ELSE NULL END,
                CASE WHEN i > 8 THEN v_now - INTERVAL '12 days' ELSE NULL END
            );
        ELSE
            v_price_old := round((v_base_cost * 1.42)::numeric, 0)::numeric(19,4);
            v_price_cur := round((v_base_cost * 1.50)::numeric, 0)::numeric(19,4);
        END IF;
        INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
        VALUES (v_list_tarjeta, v_pid, v_price_cur, v_now - INTERVAL '35 days', v_now - INTERVAL '14 days');
        INSERT INTO catalog.product_price_history (id, price_list_id, product_id, price, effective_on, created_at, updated_at) VALUES
            (gen_random_uuid(), v_list_tarjeta, v_pid, v_price_old, v_today - 35, v_now - INTERVAL '35 days', v_now - INTERVAL '35 days'),
            (gen_random_uuid(), v_list_tarjeta, v_pid, v_price_cur, v_today - 14, v_now - INTERVAL '14 days', v_now - INTERVAL '14 days');

        -- Link 0, 1, or 2 suppliers (~15% none, ~55% one, ~30% two)
        IF i % 7 <> 0 THEN
            INSERT INTO catalog.product_suppliers (product_id, supplier_id)
            VALUES (v_pid, v_supplier_ids[((i - 1) % array_length(v_supplier_ids, 1)) + 1])
            ON CONFLICT DO NOTHING;
            IF i % 3 = 0 THEN
                INSERT INTO catalog.product_suppliers (product_id, supplier_id)
                VALUES (v_pid, v_supplier_ids[((i + 4) % array_length(v_supplier_ids, 1)) + 1])
                ON CONFLICT DO NOTHING;
            END IF;
        END IF;

        -- Initialize inventory balance row (will be calibrated cleanly after all movements)
        INSERT INTO inventory.inventory_balances (product_id, quantity, updated_at)
        VALUES (v_pid, 0, v_now - INTERVAL '35 days');
    END LOOP;

    -- 11. Customers (160 total: 12 RESELLER + 36 assigned to resellers + 104 independent REGULAR + 8 INACTIVE)
    -- 11a. 12 Resellers
    FOR i IN 1..12 LOOP
        v_cid := gen_random_uuid();
        INSERT INTO customer.customers (
            id, business_name, tax_id, seller_id, balance, status, created_at, price_list_id,
            email, phone, address, zone, kind, reseller_id, reseller_kind
        ) VALUES (
            v_cid,
            v_reseller_names[i],
            '30-71' || lpad((100000 + i * 137)::text, 6, '0') || '-' || (i % 10),
            v_seller_ids[((i - 1) % 7) + 1],
            0,
            'ACTIVE',
            v_now - INTERVAL '50 days',
            CASE WHEN i % 2 = 0 THEN v_list_distribuidor ELSE v_list_mayorista END,
            'compras@revendedor' || i || '.com.ar',
            '+54 11 5' || lpad((100 + i * 41)::text, 3, '0') || '-' || lpad((2000 + i * 173)::text, 4, '0'),
            v_streets[((i - 1) % array_length(v_streets, 1)) + 1] || ' ' || (2100 + i * 190),
            v_zone_names[((i - 1) % array_length(v_zone_names, 1)) + 1],
            'RESELLER',
            NULL,
            NULL
        );
        v_reseller_ids := array_append(v_reseller_ids, v_cid);
        v_customer_ids := array_append(v_customer_ids, v_cid);
    END LOOP;

    -- 11b. 36 Regular customers under Resellers (3 per reseller)
    FOR i IN 1..36 LOOP
        v_cid := gen_random_uuid();
        INSERT INTO customer.customers (
            id, business_name, tax_id, seller_id, balance, status, created_at, price_list_id,
            email, phone, address, zone, kind, reseller_id, reseller_kind
        ) VALUES (
            v_cid,
            v_cust_prefixes[((i - 1) % array_length(v_cust_prefixes, 1)) + 1] || ' ' ||
                v_cust_suffixes[((i * 3 - 1) % array_length(v_cust_suffixes, 1)) + 1] || ' (Red ' || (((i - 1) % 12) + 1) || ')',
            CASE WHEN i % 5 = 0 THEN NULL ELSE '20-' || lpad((31000000 + i * 4123)::text, 8, '0') || '-' || (i % 10) END,
            v_seller_ids[(((i - 1) % 12) % 7) + 1],
            0,
            'ACTIVE',
            v_now - INTERVAL '45 days',
            CASE (i % 4)
                WHEN 0 THEN v_list_mayorista
                WHEN 1 THEN v_list_general
                WHEN 2 THEN v_list_distribuidor
                ELSE v_list_tarjeta
            END,
            'contacto.red' || i || '@comercio-demo.com.ar',
            '+54 11 6' || lpad((100 + i * 19)::text, 3, '0') || '-' || lpad((3100 + i * 97)::text, 4, '0'),
            v_streets[((i + 2) % array_length(v_streets, 1)) + 1] || ' ' || (400 + i * 75),
            v_zone_names[((i - 1) % array_length(v_zone_names, 1)) + 1],
            'REGULAR',
            v_reseller_ids[((i - 1) % 12) + 1],
            'RESELLER'
        );
        v_customer_ids := array_append(v_customer_ids, v_cid);
    END LOOP;

    -- 11c. 104 Active independent regular customers + 8 Inactive regular customers
    FOR i IN 1..112 LOOP
        v_cid := gen_random_uuid();
        INSERT INTO customer.customers (
            id, business_name, tax_id, seller_id, balance, status, created_at, price_list_id,
            email, phone, address, zone, kind, reseller_id, reseller_kind
        ) VALUES (
            v_cid,
            v_cust_prefixes[((i + 4) % array_length(v_cust_prefixes, 1)) + 1] || ' ' ||
                v_cust_suffixes[((i * 5 + 2) % array_length(v_cust_suffixes, 1)) + 1] || ' #' || lpad(i::text, 3, '0'),
            CASE WHEN i % 6 = 0 THEN NULL ELSE '27-' || lpad((25000000 + i * 7919)::text, 8, '0') || '-' || (i % 10) END,
            CASE WHEN i > 104 THEN v_seller_ids[8] ELSE v_seller_ids[((i - 1) % 7) + 1] END,
            0,
            CASE WHEN i <= 104 THEN 'ACTIVE' ELSE 'INACTIVE' END,
            v_now - INTERVAL '45 days',
            CASE (i % 5)
                WHEN 0 THEN NULL
                WHEN 1 THEN v_list_general
                WHEN 2 THEN v_list_mayorista
                WHEN 3 THEN v_list_distribuidor
                ELSE v_list_tarjeta
            END,
            CASE WHEN i % 4 = 0 THEN NULL ELSE 'cliente' || i || '@comercio-demo.com.ar' END,
            '+54 11 4' || lpad((300 + (i * 13) % 699)::text, 3, '0') || '-' || lpad((1000 + (i * 151) % 8999)::text, 4, '0'),
            v_streets[((i - 1) % array_length(v_streets, 1)) + 1] || ' ' || (850 + i * 43),
            v_zone_names[((i - 1) % array_length(v_zone_names, 1)) + 1],
            'REGULAR',
            NULL,
            NULL
        );
        IF i <= 104 THEN
            v_customer_ids := array_append(v_customer_ids, v_cid);
        END IF;
    END LOOP;

    -- 12. Commission Plans & Rules (4 plans)
    -- Plan 1: Vendedores Estándar
    v_pid := gen_random_uuid();
    v_plan_ids := array_append(v_plan_ids, v_pid);
    INSERT INTO app.commission_plans (id, name, version) VALUES (v_pid, 'Plan Vendedores Estándar', 1);
    INSERT INTO app.commission_rules (plan_id, category_id, brand_id, product_id, kind, value) VALUES
        (v_pid, NULL, NULL, NULL, 'PERCENT', 6.0000),
        (v_pid, v_cat_ids[1], NULL, NULL, 'PERCENT', 8.0000),
        (v_pid, v_cat_ids[2], NULL, NULL, 'FIXED', 450.0000),
        (v_pid, v_cat_ids[6], NULL, NULL, 'PERCENT', 7.5000);

    -- Plan 2: Vendedores Senior
    v_pid := gen_random_uuid();
    v_plan_ids := array_append(v_plan_ids, v_pid);
    INSERT INTO app.commission_plans (id, name, version) VALUES (v_pid, 'Plan Vendedores Senior', 2);
    INSERT INTO app.commission_rules (plan_id, category_id, brand_id, product_id, kind, value) VALUES
        (v_pid, NULL, NULL, NULL, 'PERCENT', 8.5000),
        (v_pid, v_cat_ids[3], NULL, NULL, 'PERCENT', 9.5000),
        (v_pid, NULL, v_brand_ids[1], NULL, 'FIXED', 600.0000),
        (v_pid, NULL, NULL, v_product_ids[1], 'FIXED', 750.0000);

    -- Plan 3: Revendedores Base
    v_pid := gen_random_uuid();
    v_plan_ids := array_append(v_plan_ids, v_pid);
    INSERT INTO app.commission_plans (id, name, version) VALUES (v_pid, 'Plan Revendedores Base', 1);
    INSERT INTO app.commission_rules (plan_id, category_id, brand_id, product_id, kind, value) VALUES
        (v_pid, NULL, NULL, NULL, 'PERCENT', 4.5000),
        (v_pid, v_cat_ids[2], NULL, NULL, 'PERCENT', 5.5000),
        (v_pid, v_cat_ids[6], NULL, NULL, 'PERCENT', 5.0000);

    -- Plan 4: Revendedores Premium
    v_pid := gen_random_uuid();
    v_plan_ids := array_append(v_plan_ids, v_pid);
    INSERT INTO app.commission_plans (id, name, version) VALUES (v_pid, 'Plan Revendedores Premium', 1);
    INSERT INTO app.commission_rules (plan_id, category_id, brand_id, product_id, kind, value) VALUES
        (v_pid, NULL, NULL, NULL, 'PERCENT', 6.5000),
        (v_pid, v_cat_ids[1], NULL, NULL, 'PERCENT', 7.5000),
        (v_pid, NULL, v_brand_ids[3], NULL, 'PERCENT', 7.0000);

    -- Assign plans to 6 of 7 active sellers (seller 7 left unassigned for testing)
    INSERT INTO app.seller_commission_plans (seller_id, plan_id) VALUES
        (v_seller_ids[1], v_plan_ids[2]),
        (v_seller_ids[2], v_plan_ids[2]),
        (v_seller_ids[3], v_plan_ids[1]),
        (v_seller_ids[4], v_plan_ids[1]),
        (v_seller_ids[5], v_plan_ids[1]),
        (v_seller_ids[6], v_plan_ids[2]);

    -- Assign plans to 10 of 12 resellers (resellers 11 & 12 left unassigned for testing)
    FOR i IN 1..10 LOOP
        INSERT INTO app.reseller_commission_plans (customer_id, customer_kind, plan_id)
        VALUES (v_reseller_ids[i], 'RESELLER', CASE WHEN i <= 4 THEN v_plan_ids[4] ELSE v_plan_ids[3] END);
    END LOOP;

    -- 13. Supplier Orders (95 across last 30 days: ~72 CONFIRMED, ~15 PENDING, ~8 CANCELLED)
    FOR i IN 1..95 LOOP
        v_sup_id := v_supplier_ids[((i - 1) % array_length(v_supplier_ids, 1)) + 1];
        v_day_offset := -30 + floor(((i - 1)::numeric * 30.0) / 94.0)::int;
        v_po_date := v_today + v_day_offset;
        v_po_ts := ((v_po_date::text || ' ' || lpad((9 + (i % 7))::text, 2, '0') || ':' || lpad(((i * 11) % 60)::text, 2, '0') || ':00')::timestamp AT TIME ZONE v_tz);
        v_po_id := gen_random_uuid();
        v_po_number := 'PRV-' || lpad(nextval('purchasing.supplier_order_number_seq')::text, 8, '0');

        IF v_day_offset >= -3 AND (i % 3 <> 0) THEN
            v_po_status := 'PENDING';
        ELSIF i % 12 = 0 THEN
            v_po_status := 'CANCELLED';
        ELSIF i > 82 THEN
            v_po_status := 'PENDING';
        ELSE
            v_po_status := 'CONFIRMED';
        END IF;

        v_po_total := 0;
        INSERT INTO purchasing.supplier_orders (
            id, order_number, supplier_id, supplier_name, order_date, total,
            idempotency_key, request_fingerprint, created_by, created_at,
            status, updated_at, confirmed_at, confirmed_by, cancelled_at, cancelled_by
        ) VALUES (
            v_po_id,
            v_po_number,
            v_sup_id,
            (SELECT name FROM supplier.suppliers WHERE id = v_sup_id),
            v_po_date,
            0,
            'extensive-seed-po-' || lpad(i::text, 4, '0'),
            md5('extensive-seed-po-' || i || '-' || v_po_date),
            CASE WHEN i % 3 = 0 THEN v_admin2_id ELSE v_admin_id END,
            v_po_ts,
            v_po_status,
            v_po_ts + INTERVAL '2 hours',
            CASE WHEN v_po_status = 'CONFIRMED' THEN v_po_ts + INTERVAL '3 hours' ELSE NULL END,
            CASE WHEN v_po_status = 'CONFIRMED' THEN v_admin_id ELSE NULL END,
            CASE WHEN v_po_status = 'CANCELLED' THEN v_po_ts + INTERVAL '2 hours' ELSE NULL END,
            CASE WHEN v_po_status = 'CANCELLED' THEN v_admin_id ELSE NULL END
        );

        -- 3 to 6 distinct active products linked to this supplier (or fallback to any active product)
        v_line_count := 3 + (i % 4);
        v_used_prods := ARRAY[]::UUID[];
        FOR j IN 1..v_line_count LOOP
            SELECT p.id, p.name, p.cost INTO v_prod_rec
            FROM catalog.products p
            LEFT JOIN catalog.product_suppliers ps ON ps.product_id = p.id AND ps.supplier_id = v_sup_id
            WHERE p.status = 'ACTIVE'
              AND NOT (p.id = ANY(v_used_prods))
            ORDER BY (ps.supplier_id IS NOT NULL) DESC, md5(p.id::text || '-' || i || '-' || j)
            LIMIT 1;

            v_used_prods := array_append(v_used_prods, v_prod_rec.id);
            v_po_qty := (15 + ((i * 7 + j * 13) % 45))::numeric(19,4);
            -- Historical cost slightly lower (94%-99%) so "Cargar pedido anterior" shows cost updates
            v_po_cost := round((v_prod_rec.cost * (0.94 + ((i + j) % 6) * 0.01))::numeric, 2)::numeric(19,4);
            v_po_line_total := round((v_po_qty * v_po_cost)::numeric, 4);
            v_po_total := v_po_total + v_po_line_total;

            INSERT INTO purchasing.supplier_order_items (
                id, order_id, product_id, product_name, quantity, unit_cost, line_total
            ) VALUES (
                gen_random_uuid(), v_po_id, v_prod_rec.id, v_prod_rec.name, v_po_qty, v_po_cost, v_po_line_total
            );

            IF v_po_status = 'CONFIRMED' THEN
                INSERT INTO inventory.stock_movements (
                    id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
                ) VALUES (
                    gen_random_uuid(), v_prod_rec.id, 'PURCHASE', v_po_qty, 'PURCHASE', v_po_id,
                    v_po_ts + INTERVAL '3 hours', 'Compra ' || v_po_number
                );
            END IF;
        END LOOP;

        UPDATE purchasing.supplier_orders SET total = v_po_total WHERE id = v_po_id;
    END LOOP;

    -- 14. Customer Orders & Sales across the LAST 30 DAYS (today - 30 through today)
    -- Realistic daily volume: weekdays 32..42 (slight upward trend), Sat 18..24, Sun 5..8 -> ~920 orders total!
    FOR v_day_offset IN -30..0 LOOP
        v_order_date := v_today + v_day_offset;
        v_dow := EXTRACT(ISODOW FROM v_order_date)::int; -- 1=Mon .. 7=Sun
        IF v_dow <= 5 THEN
            v_orders_for_day := 31 + ((v_day_offset + 30) / 4) + (floor(random() * 6)::int);
        ELSIF v_dow = 6 THEN
            v_orders_for_day := 17 + floor(random() * 6)::int;
        ELSE
            v_orders_for_day := 4 + floor(random() * 4)::int;
        END IF;

        -- Keep today's orders strictly <= current time (around 17:30 ART)
        FOR k IN 1..v_orders_for_day LOOP
            v_order_seq := v_order_seq + 1;
            v_order_id := gen_random_uuid();
            v_sale_id := gen_random_uuid();

            -- Pick an active customer (skewed slightly so top clients & resellers order more frequently)
            v_r := random();
            IF v_r < 0.28 THEN
                -- Reseller-affiliated customers (indices 13..48 in v_customer_ids)
                v_pick_idx := 13 + floor(random() * 36)::int;
            ELSIF v_r < 0.36 THEN
                -- Direct reseller orders (indices 1..12)
                v_pick_idx := 1 + floor(random() * 12)::int;
            ELSIF v_r < 0.65 THEN
                -- High-frequency regular customers (indices 49..85)
                v_pick_idx := 49 + floor(random() * 37)::int;
            ELSE
                -- Remaining active regular customers (indices 86..152)
                v_pick_idx := 86 + floor(random() * (array_length(v_customer_ids, 1) - 85))::int;
            END IF;

            SELECT c.id, c.seller_id, c.price_list_id, c.kind, c.reseller_id, c.balance,
                   coalesce(c.reseller_id, c.id) AS account_id
            INTO v_cust_rec
            FROM customer.customers c
            WHERE c.id = v_customer_ids[v_pick_idx];

            -- Price list for the order
            v_price_list_id := coalesce(v_cust_rec.price_list_id, v_list_general);
            SELECT code INTO v_price_list_code FROM catalog.price_lists WHERE id = v_price_list_id;

            -- Realistic business-hours timestamp (08:15 to 17:30 on today, 08:15 to 19:15 on past days)
            IF v_day_offset = 0 THEN
                v_order_ts := ((v_order_date::text || ' ' || lpad((8 + floor(random() * 9)::int)::text, 2, '0') || ':' ||
                                lpad(floor(random() * 60)::int::text, 2, '0') || ':' ||
                                lpad(floor(random() * 60)::int::text, 2, '0'))::timestamp AT TIME ZONE v_tz);
                IF v_order_ts > v_now - INTERVAL '10 minutes' THEN
                    v_order_ts := v_now - ((15 + floor(random() * 180)::int) || ' minutes')::interval;
                END IF;
            ELSE
                v_order_ts := ((v_order_date::text || ' ' || lpad((8 + floor(random() * 11)::int)::text, 2, '0') || ':' ||
                                lpad(floor(random() * 60)::int::text, 2, '0') || ':' ||
                                lpad(floor(random() * 60)::int::text, 2, '0'))::timestamp AT TIME ZONE v_tz);
            END IF;

            -- Decide status & fulfillment realistically based on age
            v_r := random();
            IF v_day_offset <= -4 THEN
                IF v_r < 0.90 THEN
                    v_order_status := 'DELIVERED';
                    v_fulfill_status := 'PREPARED';
                ELSIF v_r < 0.955 THEN
                    v_order_status := 'CANCELLED';
                    v_fulfill_status := 'PENDING';
                ELSE
                    v_order_status := 'CONFIRMED';
                    v_fulfill_status := CASE WHEN random() < 0.65 THEN 'PREPARED' ELSE 'PENDING' END;
                END IF;
            ELSIF v_day_offset IN (-3, -2) THEN
                IF v_r < 0.58 THEN
                    v_order_status := 'DELIVERED';
                    v_fulfill_status := 'PREPARED';
                ELSIF v_r < 0.64 THEN
                    v_order_status := 'CANCELLED';
                    v_fulfill_status := 'PENDING';
                ELSE
                    v_order_status := 'CONFIRMED';
                    v_fulfill_status := CASE WHEN random() < 0.55 THEN 'PREPARED' ELSE 'PENDING' END;
                END IF;
            ELSE
                -- Yesterday & today: mostly CONFIRMED (pending & prepared) + some already delivered
                IF v_r < 0.22 THEN
                    v_order_status := 'DELIVERED';
                    v_fulfill_status := 'PREPARED';
                ELSIF v_r < 0.26 THEN
                    v_order_status := 'CANCELLED';
                    v_fulfill_status := 'PENDING';
                ELSE
                    v_order_status := 'CONFIRMED';
                    v_fulfill_status := CASE WHEN random() < 0.42 THEN 'PREPARED' ELSE 'PENDING' END;
                END IF;
            END IF;

            -- Creator & review state
            v_created_by_admin := (random() < 0.35);
            IF v_created_by_admin THEN
                v_created_by_user := CASE WHEN random() < 0.75 THEN v_admin_id ELSE v_admin2_id END;
                v_reviewed_at := NULL;
                v_reviewed_by := NULL;
            ELSE
                SELECT user_id INTO v_created_by_user FROM seller.seller_profiles WHERE id = v_cust_rec.seller_id;
                IF v_day_offset <= -3 OR random() < 0.55 THEN
                    v_reviewed_at := LEAST(v_order_ts + INTERVAL '90 minutes', v_now - INTERVAL '5 minutes');
                    v_reviewed_by := CASE WHEN random() < 0.8 THEN v_admin_id ELSE v_admin2_id END;
                ELSE
                    v_reviewed_at := NULL;
                    v_reviewed_by := NULL;
                END IF;
            END IF;

            -- Timestamps for preparation, delivery, cancellation
            IF v_fulfill_status = 'PREPARED' THEN
                v_prep_ts := LEAST(v_order_ts + ((25 + floor(random() * 140)::int) || ' minutes')::interval, v_now - INTERVAL '4 minutes');
                v_prep_by := CASE WHEN random() < 0.6 THEN v_admin_id ELSE v_created_by_user END;
            ELSE
                v_prep_ts := NULL;
                v_prep_by := NULL;
            END IF;

            IF v_order_status = 'DELIVERED' THEN
                v_deliv_ts := LEAST(v_prep_ts + ((45 + floor(random() * 1440)::int) || ' minutes')::interval, v_now - INTERVAL '2 minutes');
                v_cancel_ts := NULL;
            ELSIF v_order_status = 'CANCELLED' THEN
                v_deliv_ts := NULL;
                v_cancel_ts := LEAST(v_order_ts + ((30 + floor(random() * 240)::int) || ' minutes')::interval, v_now - INTERVAL '2 minutes');
            ELSE
                v_deliv_ts := NULL;
                v_cancel_ts := NULL;
            END IF;

            -- Numbers from DB functions
            v_order_number := orders.next_customer_order_number(v_cust_rec.id);
            v_sale_number := v_order_number;

            -- Build order lines (2 to 7 distinct active products)
            v_line_count := 2 + floor(random() * 6)::int;
            v_subtotal := 0;
            v_disc_subtotal := 0;
            v_total_line_disc := 0;
            v_order_disc_pct := CASE
                WHEN random() < 0.82 THEN 0.0000
                WHEN random() < 0.50 THEN 3.0000
                WHEN random() < 0.80 THEN 5.0000
                ELSE 8.0000
            END;

            -- Pre-insert header with placeholder totals (updated right after items)
            INSERT INTO orders.orders (
                id, order_number, customer_id, seller_id, status, subtotal, discount, total,
                created_at, idempotency_key, idempotency_fingerprint,
                delivered_at, cancelled_at, credit_limit_exceeded, credit_limit_snapshot,
                projected_balance_snapshot, order_discount_percent, previous_balance_amount,
                created_by_user_id, created_by_admin,
                fulfillment_status, prepared_at, prepared_by_user_id, legacy_fulfillment,
                reviewed_at, reviewed_by_user_id
            ) VALUES (
                v_order_id, v_order_number, v_cust_rec.id, v_cust_rec.seller_id, v_order_status,
                0, 0, 0,
                v_order_ts, 'ext-ord-' || lpad(v_order_seq::text, 6, '0'), md5('ext-ord-' || v_order_seq),
                v_deliv_ts, v_cancel_ts, false, v_credit_limit,
                0, v_order_disc_pct, 0,
                v_created_by_user, v_created_by_admin,
                v_fulfill_status, v_prep_ts, v_prep_by, false,
                v_reviewed_at, v_reviewed_by
            );

            INSERT INTO sale.sales (
                id, sale_number, order_id, customer_id, status, total, paid,
                created_at, delivered_at, cancelled_at, order_discount_percent, account_customer_id
            ) VALUES (
                v_sale_id, v_sale_number, v_order_id, v_cust_rec.id, v_order_status, 0, 0,
                v_order_ts, v_deliv_ts, v_cancel_ts, v_order_disc_pct, v_cust_rec.account_id
            );

            v_used_prods := ARRAY[]::UUID[];
            FOR j IN 1..v_line_count LOOP
                -- Skew slightly towards first 40 products so top products & stock coverage look realistic
                IF random() < 0.35 THEN
                    v_pid := v_product_ids[1 + floor(random() * 40)::int];
                ELSE
                    v_pid := v_product_ids[1 + floor(random() * array_length(v_product_ids, 1))::int];
                END IF;
                WHILE v_pid = ANY(v_used_prods) LOOP
                    v_pid := v_product_ids[1 + floor(random() * array_length(v_product_ids, 1))::int];
                END LOOP;
                v_used_prods := array_append(v_used_prods, v_pid);

                -- Resolve effective historical price as of v_order_date
                SELECT p.name, h.price INTO v_prod_rec
                FROM catalog.products p
                JOIN LATERAL (
                    SELECT ph.price
                    FROM catalog.product_price_history ph
                    WHERE ph.product_id = p.id
                      AND ph.price_list_id = v_price_list_id
                      AND ph.effective_on <= v_order_date
                    ORDER BY ph.effective_on DESC, ph.created_at DESC, ph.id DESC
                    LIMIT 1
                ) h ON true
                WHERE p.id = v_pid;

                -- Quantity (mostly integers 1..14, occasionally 0.5 / 1.5 / 2.5)
                IF random() < 0.08 THEN
                    v_qty := (0.5 + floor(random() * 4)::int)::numeric(19,4);
                ELSE
                    v_qty := (1 + floor(random() * 12)::int)::numeric(19,4);
                END IF;

                v_unit_price := round(v_prod_rec.price, 4);
                v_line_disc_pct := CASE
                    WHEN random() < 0.80 THEN 0.0000
                    WHEN random() < 0.55 THEN 5.0000
                    ELSE 10.0000
                END;

                v_line_base := round((v_qty * v_unit_price)::numeric, 4);
                v_line_disc := round((v_line_base * v_line_disc_pct / 100.0)::numeric, 4);
                v_line_total := round((v_line_base - v_line_disc)::numeric, 4);

                v_subtotal := round((v_subtotal + v_line_base)::numeric, 4);
                v_disc_subtotal := round((v_disc_subtotal + v_line_total)::numeric, 4);
                v_total_line_disc := round((v_total_line_disc + v_line_disc)::numeric, 4);

                INSERT INTO orders.order_items (
                    id, order_id, product_id, product_name, quantity, unit_price, line_total,
                    price_list_id, price_list_code, line_discount_percent
                ) VALUES (
                    gen_random_uuid(), v_order_id, v_pid, v_prod_rec.name, v_qty, v_unit_price, v_line_total,
                    v_price_list_id, v_price_list_code, v_line_disc_pct
                );

                INSERT INTO sale.sale_items (
                    id, sale_id, product_id, product_name, quantity, unit_price, line_total,
                    price_list_id, price_list_code, line_discount_percent
                ) VALUES (
                    gen_random_uuid(), v_sale_id, v_pid, v_prod_rec.name, v_qty, v_unit_price, v_line_total,
                    v_price_list_id, v_price_list_code, v_line_disc_pct
                );

                -- Stock movements ONLY for PREPARED/DELIVERED orders (and reversed for cancelled if ever prepared)
                IF v_fulfill_status = 'PREPARED' THEN
                    INSERT INTO inventory.stock_movements (
                        id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
                    ) VALUES (
                        gen_random_uuid(), v_pid, 'SALE', -v_qty, 'SALE', v_order_id, v_prep_ts, 'Preparación de pedido ' || v_order_number
                    );
                END IF;
            END LOOP;

            v_order_disc := round((v_disc_subtotal * v_order_disc_pct / 100.0)::numeric, 4);
            v_total := round((v_disc_subtotal - v_order_disc)::numeric, 4);

            -- Read current account balance BEFORE this order
            SELECT balance INTO v_cur_bal FROM customer.customers WHERE id = v_cust_rec.account_id;
            v_prev_bal := CASE
                WHEN v_cust_rec.reseller_id IS NULL AND v_cur_bal > 0 AND random() < 0.30 THEN v_cur_bal
                ELSE 0.0000
            END;

            -- Determine payments & account ledger entries
            v_paid_upfront := 0;
            v_paid_later := 0;

            IF v_order_status = 'CANCELLED' THEN
                -- Unpaid order debited on confirm, credited in full on cancellation (net = 0)
                v_proj_bal := round((v_cur_bal + v_total)::numeric, 4);
                INSERT INTO customer.account_ledger (id, customer_id, sale_id, entry_type, amount, created_at) VALUES
                    (gen_random_uuid(), v_cust_rec.account_id, v_sale_id, 'DEBIT', v_total, v_order_ts),
                    (gen_random_uuid(), v_cust_rec.account_id, v_sale_id, 'CREDIT', v_total, v_cancel_ts);
            ELSE
                -- Upfront payment at confirmation (only ~20% of non-reseller orders, or ~10% admin reseller orders)
                IF (v_cust_rec.kind <> 'RESELLER' AND v_cust_rec.reseller_id IS NULL AND random() < 0.22)
                   OR (v_created_by_admin AND random() < 0.12) THEN
                    IF random() < 0.65 THEN
                        v_paid_upfront := v_total;
                    ELSE
                        v_paid_upfront := round((v_total * 0.50)::numeric, 2)::numeric(19,4);
                    END IF;
                END IF;

                v_account_debit := round((v_total - v_paid_upfront)::numeric, 4);
                v_proj_bal := round((v_cur_bal + v_account_debit)::numeric, 4);

                IF v_paid_upfront > 0 THEN
                    v_pay_method := CASE WHEN random() < 0.52 THEN 'CASH' ELSE 'BANK_TRANSFER' END;
                    v_pay_ref := CASE WHEN v_pay_method = 'BANK_TRANSFER'
                        THEN 'TRF-' || to_char(v_order_date, 'YYYYMMDD') || '-' || lpad(v_order_seq::text, 5, '0')
                        ELSE NULL END;
                    INSERT INTO payment.payments (id, sale_id, customer_id, amount, method, created_at, transfer_reference)
                    VALUES (gen_random_uuid(), v_sale_id, v_cust_rec.account_id, v_paid_upfront, v_pay_method, v_order_ts, v_pay_ref);
                END IF;

                IF v_account_debit > 0 THEN
                    INSERT INTO customer.account_ledger (id, customer_id, sale_id, entry_type, amount, created_at)
                    VALUES (gen_random_uuid(), v_cust_rec.account_id, v_sale_id, 'DEBIT', v_account_debit, v_order_ts);
                    UPDATE customer.customers SET balance = balance + v_account_debit WHERE id = v_cust_rec.account_id;

                    -- Collection at delivery or later via account payment
                    IF v_order_status = 'DELIVERED' THEN
                        v_r := random();
                        IF v_r < 0.66 THEN
                            v_paid_later := v_account_debit; -- fully paid
                        ELSIF v_r < 0.84 THEN
                            v_paid_later := round((v_account_debit * (0.35 + floor(random() * 4) * 0.12))::numeric, 2)::numeric(19,4); -- partial
                        ELSE
                            v_paid_later := 0; -- still open debt!
                        END IF;
                    ELSIF v_day_offset <= -2 AND random() < 0.15 THEN
                        v_paid_later := round((v_account_debit * 0.40)::numeric, 2)::numeric(19,4);
                    END IF;

                    IF v_paid_later > 0 THEN
                        v_pay_ts := COALESCE(v_deliv_ts, v_order_ts + INTERVAL '1 day');
                        IF v_order_status = 'DELIVERED' AND random() < 0.35 THEN
                            v_pay_ts := LEAST(v_deliv_ts + ((1 + floor(random() * 5)::int) || ' days')::interval, v_now - INTERVAL '1 minute');
                        END IF;
                        v_pay_method := CASE WHEN random() < 0.50 THEN 'CASH' ELSE 'BANK_TRANSFER' END;
                        v_pay_ref := CASE WHEN v_pay_method = 'BANK_TRANSFER'
                            THEN 'TRF-' || to_char((v_pay_ts AT TIME ZONE v_tz)::date, 'YYYYMMDD') || '-' || lpad((v_order_seq + 50000)::text, 5, '0')
                            ELSE NULL END;
                        INSERT INTO payment.payments (id, sale_id, customer_id, amount, method, created_at, transfer_reference)
                        VALUES (gen_random_uuid(), v_sale_id, v_cust_rec.account_id, v_paid_later, v_pay_method, v_pay_ts, v_pay_ref);
                        INSERT INTO customer.account_ledger (id, customer_id, sale_id, entry_type, amount, created_at)
                        VALUES (gen_random_uuid(), v_cust_rec.account_id, v_sale_id, 'CREDIT', v_paid_later, v_pay_ts);
                        UPDATE customer.customers SET balance = balance - v_paid_later WHERE id = v_cust_rec.account_id;
                    END IF;
                END IF;
            END IF;

            v_paid_total := round((v_paid_upfront + v_paid_later)::numeric, 4);
            v_credit_exceeded := (v_proj_bal > v_credit_limit);

            UPDATE orders.orders
            SET subtotal = v_subtotal,
                discount = round((v_total_line_disc + v_order_disc)::numeric, 4),
                total = v_total,
                credit_limit_exceeded = v_credit_exceeded,
                projected_balance_snapshot = v_proj_bal,
                previous_balance_amount = v_prev_bal
            WHERE id = v_order_id;

            UPDATE sale.sales
            SET total = v_total,
                paid = v_paid_total
            WHERE id = v_sale_id;

            -- Delivery attempts
            SELECT user_id INTO v_attempt_by FROM seller.seller_profiles WHERE id = v_cust_rec.seller_id;
            IF v_order_status = 'DELIVERED' THEN
                IF random() < 0.13 THEN
                    -- Failed first attempt, then delivered on attempt 2
                    INSERT INTO orders.delivery_attempts (id, order_id, attempt_number, result, observation, attempted_by, attempted_at)
                    VALUES (
                        gen_random_uuid(), v_order_id, 1, 'FAILED',
                        v_fail_reasons[1 + (v_order_seq % array_length(v_fail_reasons, 1))],
                        v_attempt_by,
                        GREATEST(v_prep_ts, v_deliv_ts - INTERVAL '6 hours')
                    );
                    INSERT INTO orders.delivery_attempts (id, order_id, attempt_number, result, observation, attempted_by, attempted_at)
                    VALUES (
                        gen_random_uuid(), v_order_id, 2, 'DELIVERED',
                        'Entregado en segunda visita',
                        v_attempt_by,
                        v_deliv_ts
                    );
                ELSE
                    INSERT INTO orders.delivery_attempts (id, order_id, attempt_number, result, observation, attempted_by, attempted_at)
                    VALUES (
                        gen_random_uuid(), v_order_id, 1, 'DELIVERED',
                        CASE WHEN v_order_seq % 4 = 0 THEN 'Recibió encargado de depósito' ELSE NULL END,
                        v_attempt_by,
                        v_deliv_ts
                    );
                END IF;
            ELSIF v_order_status = 'CONFIRMED' AND v_fulfill_status = 'PREPARED' AND random() < 0.25 THEN
                -- Active confirmed order with a FAILED delivery attempt (lights up dashboard failedDeliveries!)
                INSERT INTO orders.delivery_attempts (id, order_id, attempt_number, result, observation, attempted_by, attempted_at)
                VALUES (
                    gen_random_uuid(), v_order_id, 1, 'FAILED',
                    v_fail_reasons[1 + (v_order_seq % array_length(v_fail_reasons, 1))],
                    v_attempt_by,
                    LEAST(v_prep_ts + INTERVAL '3 hours', v_now - INTERVAL '2 minutes')
                );
            END IF;
        END LOOP;
    END LOOP;

    -- 15. Accrue commissions for ALL DELIVERED sales (exact CommissionAccrualService.delivered formula!)
    WITH delivered AS (
        SELECT s.id, s.total, s.delivered_at, coalesce(o.seller_id, c.seller_id) AS seller_ref, s.account_customer_id
        FROM sale.sales s
        JOIN orders.orders o ON o.id = s.order_id
        JOIN customer.customers c ON c.id = s.customer_id
        WHERE s.status = 'DELIVERED'
    ), beneficiaries AS (
        SELECT d.id AS sale_id, d.total, d.delivered_at, sp.id AS seller_id, NULL::uuid AS reseller_id, a.plan_id
        FROM delivered d
        JOIN seller.seller_profiles sp ON sp.id = d.seller_ref AND sp.status = 'ACTIVE'
        JOIN app.seller_commission_plans a ON a.seller_id = sp.id
        UNION ALL
        SELECT d.id AS sale_id, d.total, d.delivered_at, NULL::uuid, r.id, a.plan_id
        FROM delivered d
        JOIN customer.customers r ON r.id = d.account_customer_id AND r.kind = 'RESELLER' AND r.status = 'ACTIVE'
        JOIN app.reseller_commission_plans a ON a.customer_id = r.id
    ), weighted AS (
        SELECT d.id AS sale_id, d.total, i.id, i.quantity, i.line_total, i.product_id,
               sum(i.line_total) OVER (PARTITION BY d.id) AS subtotal,
               sum(i.line_total) OVER (PARTITION BY d.id ORDER BY i.id ROWS UNBOUNDED PRECEDING) AS cumulative
        FROM delivered d
        JOIN sale.sale_items i ON i.sale_id = d.id
    ), rated AS (
        SELECT w.id, w.quantity, b.seller_id, b.reseller_id, b.delivered_at, p.cost,
               plan.id AS plan_id, plan.version, rule.kind, rule.value,
               CASE WHEN w.subtotal > 0
                   THEN round(w.total * w.cumulative / w.subtotal, 4)
                        - round(w.total * (w.cumulative - w.line_total) / w.subtotal, 4)
                   ELSE 0 END AS revenue
        FROM weighted w
        JOIN beneficiaries b ON b.sale_id = w.sale_id
        JOIN app.commission_plans plan ON plan.id = b.plan_id
        JOIN catalog.products p ON p.id = w.product_id
        JOIN LATERAL (
            SELECT r.kind, r.value
            FROM app.commission_rules r
            WHERE r.plan_id = plan.id AND (
                r.product_id = p.id OR r.brand_id = p.brand_id
                OR r.category_id = p.category_id
                OR num_nonnulls(r.product_id, r.brand_id, r.category_id) = 0
            )
            ORDER BY (r.product_id IS NOT NULL) DESC, (r.brand_id IS NOT NULL) DESC, (r.category_id IS NOT NULL) DESC
            LIMIT 1
        ) rule ON true
    ), earned AS (
        SELECT *, round(CASE WHEN kind = 'FIXED' THEN value * quantity
                             ELSE greatest(revenue - cost * quantity, 0) * value / 100 END, 4) AS amount
        FROM rated
    )
    INSERT INTO sale.commission_entries (
        seller_id, reseller_id, sale_item_id, plan_id, plan_version,
        rule_kind, rule_value, unit_cost, line_revenue, amount, occurred_at
    )
    SELECT seller_id, reseller_id, id, plan_id, version, kind, value, cost, revenue, amount, delivered_at
    FROM earned
    WHERE amount > 0
    ON CONFLICT (sale_item_id, return_id, beneficiary) DO NOTHING;

    -- 16. Sale Returns (28 partial returns on DELIVERED non-reseller sales) + stock RETURN + negative commissions
    i := 0;
    FOR v_sale_rec IN
        SELECT s.id AS sale_id, s.delivered_at
        FROM sale.sales s
        JOIN customer.customers ac ON ac.id = s.account_customer_id
        WHERE s.status = 'DELIVERED'
          AND ac.kind <> 'RESELLER'
          AND s.delivered_at < v_now - INTERVAL '2 days'
        ORDER BY md5(s.id::text)
        LIMIT 28
    LOOP
        i := i + 1;
        v_return_id := gen_random_uuid();
        v_ret_ts := LEAST(v_sale_rec.delivered_at + ((1 + (i % 3)) || ' days')::interval, v_now - INTERVAL '1 hour');
        v_ret_reason := v_return_reasons[1 + (i % array_length(v_return_reasons, 1))];

        INSERT INTO sale.returns (id, sale_id, returned_by, reason, created_at)
        VALUES (v_return_id, v_sale_rec.sale_id, v_admin_id, v_ret_reason, v_ret_ts);

        -- Return 1 or 2 items from that sale (partial quantity)
        FOR v_line_rec IN
            SELECT id, product_id, quantity
            FROM sale.sale_items
            WHERE sale_id = v_sale_rec.sale_id
            ORDER BY id
            LIMIT (1 + (i % 2))
        LOOP
            v_ret_qty := CASE
                WHEN v_line_rec.quantity >= 4 THEN 2.0000
                WHEN v_line_rec.quantity >= 2 THEN 1.0000
                ELSE 0.5000
            END;
            INSERT INTO sale.return_items (id, return_id, sale_id, sale_item_id, product_id, quantity)
            VALUES (gen_random_uuid(), v_return_id, v_sale_rec.sale_id, v_line_rec.id, v_line_rec.product_id, v_ret_qty);

            INSERT INTO inventory.stock_movements (
                id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
            ) VALUES (
                gen_random_uuid(), v_line_rec.product_id, 'RETURN', v_ret_qty, 'RETURN', v_return_id, v_ret_ts, v_ret_reason
            );
        END LOOP;

        -- Accrue negative commission adjustments (exact CommissionAccrualService.returned formula!)
        WITH adjusted AS (
            SELECT e.*, ri.quantity AS returned_now, si.quantity AS sold,
                   (SELECT sum(r.quantity) FROM sale.return_items r WHERE r.sale_item_id = si.id) AS returned_total
            FROM sale.return_items ri
            JOIN sale.sale_items si ON si.id = ri.sale_item_id
            JOIN sale.commission_entries e ON e.sale_item_id = si.id AND e.return_id IS NULL
            WHERE ri.return_id = v_return_id
        ), deltas AS (
            SELECT *, round(amount * (returned_total - returned_now) / sold, 4)
                         - round(amount * returned_total / sold, 4) AS delta
            FROM adjusted
        )
        INSERT INTO sale.commission_entries (
            seller_id, reseller_id, sale_item_id, return_id, plan_id, plan_version,
            rule_kind, rule_value, unit_cost, line_revenue, amount, occurred_at
        )
        SELECT seller_id, reseller_id, sale_item_id, v_return_id, plan_id, plan_version,
               rule_kind, rule_value, unit_cost, line_revenue, delta, v_ret_ts
        FROM deltas
        WHERE delta < 0
        ON CONFLICT (sale_item_id, return_id, beneficiary) DO NOTHING;
    END LOOP;

    -- 17. Commission Settlements for the first 20 days (days -30..-11 settled at day -10; days -10..0 left PENDING!)
    v_settle_cutoff := ((v_today - 10)::text || ' 00:00:00')::timestamp AT TIME ZONE v_tz;
    v_settle_paid_at := ((v_today - 10)::text || ' 16:30:00')::timestamp AT TIME ZONE v_tz;

    -- Seller settlements
    FOR v_ben_rec IN
        SELECT seller_id, sum(amount) AS total_amt
        FROM sale.commission_entries
        WHERE seller_id IS NOT NULL AND settlement_id IS NULL AND occurred_at < v_settle_cutoff
        GROUP BY seller_id
        HAVING sum(amount) > 0
    LOOP
        v_settlement_id := gen_random_uuid();
        INSERT INTO sale.commission_settlements (id, seller_id, reseller_id, amount, paid_at, paid_by)
        VALUES (v_settlement_id, v_ben_rec.seller_id, NULL, v_ben_rec.total_amt, v_settle_paid_at, v_admin_id);

        UPDATE sale.commission_entries
        SET settlement_id = v_settlement_id
        WHERE seller_id = v_ben_rec.seller_id AND settlement_id IS NULL AND occurred_at < v_settle_cutoff;
    END LOOP;

    -- Reseller settlements
    FOR v_ben_rec IN
        SELECT reseller_id, sum(amount) AS total_amt
        FROM sale.commission_entries
        WHERE reseller_id IS NOT NULL AND settlement_id IS NULL AND occurred_at < v_settle_cutoff
        GROUP BY reseller_id
        HAVING sum(amount) > 0
    LOOP
        v_settlement_id := gen_random_uuid();
        INSERT INTO sale.commission_settlements (id, seller_id, reseller_id, amount, paid_at, paid_by)
        VALUES (v_settlement_id, NULL, v_ben_rec.reseller_id, v_ben_rec.total_amt, v_settle_paid_at, v_admin_id);

        UPDATE sale.commission_entries
        SET settlement_id = v_settlement_id
        WHERE reseller_id = v_ben_rec.reseller_id AND settlement_id IS NULL AND occurred_at < v_settle_cutoff;
    END LOOP;

    -- 18. Inventory calibration: add MANUAL_ADJUSTMENT, SHRINKAGE, and initial MANUAL_ENTRY
    --     so ~8 active products sit at 0 stock (alerts), ~12 have 3-7 days coverage, rest healthy!
    FOR i IN 1..array_length(v_product_ids, 1) LOOP
        v_pid := v_product_ids[i];

        -- Add some shrinkages (22 products) and manual adjustments (26 products)
        IF i <= 22 THEN
            v_shrink_qty := (1 + (i % 5))::numeric(19,4);
            INSERT INTO inventory.stock_movements (
                id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
            ) VALUES (
                gen_random_uuid(), v_pid, 'SHRINKAGE', -v_shrink_qty, NULL, NULL,
                v_now - ((2 + (i % 24)) || ' days')::interval,
                v_shrink_reasons[1 + (i % array_length(v_shrink_reasons, 1))]
            );
        END IF;

        IF i > 20 AND i <= 46 THEN
            v_adj_delta := CASE WHEN i % 2 = 0 THEN (2 + (i % 6))::numeric(19,4) ELSE -(1 + (i % 4))::numeric(19,4) END;
            INSERT INTO inventory.stock_movements (
                id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
            ) VALUES (
                gen_random_uuid(), v_pid, 'MANUAL_ADJUSTMENT', v_adj_delta, NULL, NULL,
                v_now - ((1 + (i % 20)) || ' days')::interval,
                v_adj_reasons[1 + (i % array_length(v_adj_reasons, 1))]
            );
        END IF;

        -- Compute net movements so far and 30d sales volume
        SELECT coalesce(sum(quantity), 0) INTO v_net_moves
        FROM inventory.stock_movements WHERE product_id = v_pid;

        SELECT coalesce(sum(si.quantity), 0) INTO v_sold_30d
        FROM sale.sale_items si
        JOIN sale.sales s ON s.id = si.sale_id
        WHERE si.product_id = v_pid AND s.status IN ('CONFIRMED', 'DELIVERED');

        -- Target ending stock
        IF i <= 8 THEN
            -- 8 products out of stock (triggers Dashboard stockAlerts!)
            v_target_stock := 0;
        ELSIF i <= 20 THEN
            -- 12 products with tight 3..7 day coverage (shows nicely in Dashboard stockCoverage!)
            v_target_stock := GREATEST(3, round((GREATEST(v_sold_30d, 30) / 30.0) * (3 + (i % 5)))::numeric);
        ELSE
            -- Healthy stock (45..360 units)
            v_target_stock := 45 + ((i * 29) % 315);
        END IF;

        v_initial_stock := v_target_stock - v_net_moves;
        -- Ensure initial MANUAL_ENTRY is positive (if purchases already exceeded sales+target, adjust target upward)
        IF v_initial_stock <= 0 THEN
            IF i <= 8 THEN
                -- Force exact 0 via a shrinkage/adjustment on older date so alert still holds
                INSERT INTO inventory.stock_movements (
                    id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
                ) VALUES (
                    gen_random_uuid(), v_pid, 'MANUAL_ADJUSTMENT', -v_net_moves, NULL, NULL,
                    v_now - INTERVAL '3 days', 'Ajuste por conteo físico — sin stock disponible'
                );
            ELSE
                -- Give initial entry of 40 so history starts cleanly with positive stock
                INSERT INTO inventory.stock_movements (
                    id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
                ) VALUES (
                    gen_random_uuid(), v_pid, 'MANUAL_ENTRY', 40.0000, NULL, NULL,
                    v_now - INTERVAL '35 days', 'Stock inicial de apertura'
                );
            END IF;
        ELSE
            -- Round to nearest 0.5 multiple
            v_initial_stock := round(v_initial_stock * 2.0) / 2.0;
            IF v_initial_stock > 0 THEN
                INSERT INTO inventory.stock_movements (
                    id, product_id, movement_type, quantity, reference_type, reference_id, created_at, reason
                ) VALUES (
                    gen_random_uuid(), v_pid, 'MANUAL_ENTRY', v_initial_stock, NULL, NULL,
                    v_now - INTERVAL '35 days', 'Stock inicial de apertura'
                );
            END IF;
        END IF;
    END LOOP;

    -- Sync inventory.inventory_balances strictly with SUM(stock_movements)
    UPDATE inventory.inventory_balances ib
    SET quantity = coalesce((SELECT sum(m.quantity) FROM inventory.stock_movements m WHERE m.product_id = ib.product_id), 0),
        updated_at = v_now;

    -- 19. Expenses (48 across last 30 days across all 4 types)
    FOR i IN 1..48 LOOP
        v_day_offset := -30 + floor(((i - 1)::numeric * 30.0) / 47.0)::int;
        v_exp_ts := LEAST(
            (((v_today + v_day_offset)::text || ' ' || lpad((10 + (i % 7))::text, 2, '0') || ':' || lpad(((i * 13) % 60)::text, 2, '0') || ':00')::timestamp AT TIME ZONE v_tz),
            v_now - INTERVAL '15 minutes'
        );
        CASE (i % 4)
            WHEN 0 THEN
                v_exp_type := 'FUEL';
                v_exp_amt := round((38000 + random() * 62000)::numeric, 2);
                v_exp_obs := v_fuel_obs[1 + (i % array_length(v_fuel_obs, 1))];
            WHEN 1 THEN
                v_exp_type := 'SERVICES';
                v_exp_amt := round((55000 + random() * 240000)::numeric, 2);
                v_exp_obs := v_service_obs[1 + (i % array_length(v_service_obs, 1))];
            WHEN 2 THEN
                v_exp_type := 'PETTY_CASH';
                v_exp_amt := round((8500 + random() * 26500)::numeric, 2);
                v_exp_obs := v_petty_obs[1 + (i % array_length(v_petty_obs, 1))];
            ELSE
                v_exp_type := 'OTHER';
                v_exp_amt := round((18000 + random() * 95000)::numeric, 2);
                v_exp_obs := v_other_obs[1 + (i % array_length(v_other_obs, 1))];
        END CASE;

        INSERT INTO expense.expenses (id, amount, type, observation, created_by, created_at)
        VALUES (
            gen_random_uuid(),
            v_exp_amt,
            v_exp_type,
            v_exp_obs,
            CASE WHEN i % 3 = 0 THEN v_admin2_id ELSE v_admin_id END,
            v_exp_ts
        );
    END LOOP;

    -- 20. Audit Events (~260 representative events across the 30 days)
    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    VALUES (
        gen_random_uuid(), v_admin_id, 'GLOBAL_CREDIT_LIMIT_UPDATE', 'BUSINESS_SETTINGS', '1', 'SUCCESS',
        'seed-corr-credit', jsonb_build_object('creditLimit', v_credit_limit), v_now - INTERVAL '32 days'
    );

    -- Recent order confirmations in audit log
    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), o.created_by_user_id, 'ORDER_CONFIRM', 'ORDER', o.id::text, 'SUCCESS',
           'seed-ord-' || o.order_number,
           jsonb_build_object('orderNumber', o.order_number, 'total', o.total, 'creditLimitExceeded', o.credit_limit_exceeded),
           o.created_at
    FROM orders.orders o
    ORDER BY o.created_at DESC
    LIMIT 120;

    -- Recent deliveries in audit log
    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), da.attempted_by, 'DELIVERY_ATTEMPT', 'ORDER', da.order_id::text, 'SUCCESS',
           'seed-del-' || da.id::text,
           jsonb_build_object('attemptNumber', da.attempt_number, 'result', da.result),
           da.attempted_at
    FROM orders.delivery_attempts da
    ORDER BY da.attempted_at DESC
    LIMIT 60;

    -- Payments, returns, expenses, supplier orders, settlements in audit log
    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), v_admin_id, 'ACCOUNT_PAYMENT_APPLY', 'CUSTOMER', p.customer_id::text, 'SUCCESS',
           'seed-pay-' || p.id::text,
           jsonb_build_object('amount', p.amount, 'method', p.method),
           p.created_at
    FROM payment.payments p
    ORDER BY p.created_at DESC
    LIMIT 40;

    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), r.returned_by, 'SALE_RETURN', 'SALE', r.sale_id::text, 'SUCCESS',
           'seed-ret-' || r.id::text,
           jsonb_build_object('returnId', r.id::text, 'reason', r.reason),
           r.created_at
    FROM sale.returns r;

    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), e.created_by, 'EXPENSE_CREATE', 'EXPENSE', e.id::text, 'SUCCESS',
           'seed-exp-' || e.id::text,
           jsonb_build_object('amount', e.amount, 'type', e.type),
           e.created_at
    FROM expense.expenses e
    ORDER BY e.created_at DESC
    LIMIT 25;

    INSERT INTO audit.audit_events (id, actor_user_id, operation, resource_type, resource_id, result, correlation_id, details, created_at)
    SELECT gen_random_uuid(), cs.paid_by, 'COMMISSION_SETTLEMENT', 'COMMISSION', cs.id::text, 'SUCCESS',
           'seed-set-' || cs.id::text,
           jsonb_build_object('amount', cs.amount, 'sellerId', cs.seller_id, 'resellerId', cs.reseller_id),
           cs.paid_at
    FROM sale.commission_settlements cs;

    -- Record seed run marker
    INSERT INTO demo.seed_runs (name, created_at)
    VALUES ('extensive-30d-v1', v_now)
    ON CONFLICT (name) DO UPDATE SET created_at = EXCLUDED.created_at;

    -- Final strict invariant check: verify every customer balance matches account_ledger!
    IF EXISTS (
        SELECT 1
        FROM customer.customers c
        LEFT JOIN (
            SELECT customer_id,
                   sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS ledger_bal
            FROM customer.account_ledger
            GROUP BY customer_id
        ) l ON l.customer_id = c.id
        WHERE c.balance <> coalesce(l.ledger_bal, 0)
    ) THEN
        RAISE EXCEPTION 'Invariante rota: customer.balance difiere de account_ledger';
    END IF;

    -- Verify every active sale's (total - paid) matches its net ledger!
    IF EXISTS (
        SELECT 1
        FROM sale.sales s
        LEFT JOIN (
            SELECT sale_id,
                   sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS sale_bal
            FROM customer.account_ledger
            GROUP BY sale_id
        ) l ON l.sale_id = s.id
        WHERE s.status IN ('CONFIRMED', 'DELIVERED')
          AND (s.total - s.paid) <> coalesce(l.sale_bal, 0)
    ) THEN
        RAISE EXCEPTION 'Invariante rota: sale (total - paid) difiere de account_ledger';
    END IF;
END $$;

COMMIT;
