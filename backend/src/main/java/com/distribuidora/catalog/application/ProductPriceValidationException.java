package com.distribuidora.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class ProductPriceValidationException extends RuntimeException {

    public record AffectedPriceList(UUID priceListId, String code, BigDecimal currentPrice) { }

    private final List<AffectedPriceList> affectedPriceLists;

    public ProductPriceValidationException(String message, List<AffectedPriceList> affectedPriceLists) {
        super(message);
        this.affectedPriceLists = affectedPriceLists != null ? affectedPriceLists : List.of();
    }

    public List<AffectedPriceList> getAffectedPriceLists() {
        return affectedPriceLists;
    }
}
