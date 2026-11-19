package com.distribuidora.pricing.infrastructure;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

@Component
public class InitialPriceListMigrationCallback implements Callback {
    @Override
    public boolean supports(Event event, Context context) {
        if (event != Event.AFTER_EACH_MIGRATE || context.getMigrationInfo().getVersion() == null) {
            return false;
        }
        String version = context.getMigrationInfo().getVersion().getVersion();
        return version.equals("5") || version.equals("9");
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        // Runs in the seed migration transaction. Previously applied migrations stay untouched.
        try (var statement = context.getConnection().createStatement()) {
            if (context.getMigrationInfo().getVersion().getVersion().equals("5")) {
                statement.executeUpdate("""
                    delete from catalog.product_prices
                    where price_list_id in (select id from catalog.price_lists where code in ('LISTA_2', 'LISTA_3'))
                    """);
                statement.executeUpdate("delete from catalog.price_lists where code in ('LISTA_2', 'LISTA_3')");
            } else {
                statement.executeUpdate("""
                    delete from catalog.price_lists
                    where code in ('LISTA_4', 'LISTA_5', 'LISTA_6', 'LISTA_7', 'LISTA_8', 'LISTA_9', 'LISTA_10')
                      and created_at = current_timestamp
                    """);
            }
        } catch (SQLException exception) {
            throw new FlywayException("Could not keep only the default price list from the seed migration", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "initialPriceList";
    }
}
