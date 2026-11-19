package com.distribuidora.sale.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommissionAccrualService {
    private final JdbcTemplate jdbc;

    public CommissionAccrualService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Accrues the commissions of a delivered sale with the plans assigned at this moment: one for the active
     * seller of the order (or of its customer) and one for the active reseller that holds the sale's account.
     */
    public void delivered(UUID saleId, Timestamp occurredAt) {
        jdbc.update("""
            with delivered as (
                select s.id, s.total, coalesce(o.seller_id, c.seller_id) as seller_ref, s.account_customer_id
                from sale.sales s
                join orders.orders o on o.id = s.order_id
                join customer.customers c on c.id = s.customer_id
                where s.id = ? and s.status = 'DELIVERED'
            ), beneficiaries as (
                select sp.id as seller_id, null::uuid as reseller_id, a.plan_id
                from delivered d
                join seller.seller_profiles sp on sp.id = d.seller_ref and sp.status = 'ACTIVE'
                join app.seller_commission_plans a on a.seller_id = sp.id
                union all
                select null::uuid, r.id, a.plan_id
                from delivered d
                join customer.customers r on r.id = d.account_customer_id and r.kind = 'RESELLER' and r.status = 'ACTIVE'
                join app.reseller_commission_plans a on a.customer_id = r.id
            ), weighted as (
                select d.total, i.id, i.quantity, i.line_total, i.product_id,
                       sum(i.line_total) over () as subtotal,
                       sum(i.line_total) over (order by i.id rows unbounded preceding) as cumulative
                from delivered d
                join sale.sale_items i on i.sale_id = d.id
            ), rated as (
                select w.id, w.quantity, b.seller_id, b.reseller_id, p.cost, plan.id as plan_id, plan.version,
                       rule.kind, rule.value,
                       case when w.subtotal > 0
                           then round(w.total * w.cumulative / w.subtotal, 4)
                                - round(w.total * (w.cumulative - w.line_total) / w.subtotal, 4)
                           else 0 end as revenue
                from weighted w
                cross join beneficiaries b
                join app.commission_plans plan on plan.id = b.plan_id
                join catalog.products p on p.id = w.product_id
                join lateral (
                    select r.kind, r.value
                    from app.commission_rules r
                    where r.plan_id = plan.id and (r.product_id = p.id or r.brand_id = p.brand_id
                        or r.category_id = p.category_id or num_nonnulls(r.product_id, r.brand_id, r.category_id) = 0)
                    order by (r.product_id is not null) desc, (r.brand_id is not null) desc, (r.category_id is not null) desc
                    limit 1
                ) rule on true
            ), earned as (
                select *, round(case when kind = 'FIXED' then value * quantity
                                     else greatest(revenue - cost * quantity, 0) * value / 100 end, 4) as amount
                from rated
            )
            insert into sale.commission_entries(seller_id, reseller_id, sale_item_id, plan_id, plan_version, rule_kind,
                rule_value, unit_cost, line_revenue, amount, occurred_at)
            select seller_id, reseller_id, id, plan_id, version, kind, value, cost, revenue, amount, ?
            from earned where amount > 0
            on conflict (sale_item_id, return_id, beneficiary) do nothing
            """, saleId, occurredAt);
    }

    public void returned(UUID returnId, Timestamp occurredAt) {
        jdbc.update("""
            with adjusted as (
                select e.*, ri.quantity as returned_now, si.quantity as sold,
                       (select sum(r.quantity) from sale.return_items r where r.sale_item_id = si.id) as returned_total
                from sale.return_items ri
                join sale.sale_items si on si.id = ri.sale_item_id
                join sale.commission_entries e on e.sale_item_id = si.id and e.return_id is null
                where ri.return_id = ?
            ), deltas as (
                select *, round(amount * (returned_total - returned_now) / sold, 4)
                             - round(amount * returned_total / sold, 4) as delta
                from adjusted
            )
            insert into sale.commission_entries(seller_id, reseller_id, sale_item_id, return_id, plan_id, plan_version,
                rule_kind, rule_value, unit_cost, line_revenue, amount, occurred_at)
            select seller_id, reseller_id, sale_item_id, ?, plan_id, plan_version, rule_kind, rule_value, unit_cost,
                   line_revenue, delta, ?
            from deltas where delta < 0
            on conflict (sale_item_id, return_id, beneficiary) do nothing
            """, returnId, returnId, occurredAt);
    }
}
