package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminReadQueryService {
    private final ReadQuerySupport support;

    @Autowired
    public AdminReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.support = new ReadQuerySupport(jdbc, currentUser);
    }

    public PageResponse<Map<String, Object>> auditEvents(int page, int size, String search) {
        String term = ReadQuerySupport.escapedLike(search);
        String where = "";
        Object[] params = new Object[0];
        if (term != null) {
            where = """
                 where lower(ae.operation) like ? escape '!' or lower(ae.resource_type) like ? escape '!'
                    or lower(coalesce(ae.resource_id, '')) like ? escape '!' or lower(ae.result) like ? escape '!'
                """;
            params = new Object[]{term, term, term, term};
        }
        return support.page("""
                select ae.id, ae.actor_user_id as "actorUserId", u.email as actor,
                       ae.operation, ae.resource_type as "resourceType", ae.resource_id as "resourceId",
                       ae.result, ae.correlation_id as "correlationId", ae.details::text as details,
                       ae.created_at as "createdAt"
                from audit.audit_events ae left join identity.users u on u.id = ae.actor_user_id
                """ + where + """
                order by ae.created_at desc, ae.id desc
                """, "select count(*) from audit.audit_events ae" + where, page, size, params);
    }

    public PageResponse<Map<String, Object>> users(int page, int size, String search) {
        String term = ReadQuerySupport.escapedLike(search);
        String where = term == null ? "" : " where lower(u.email) like ? escape '!'";
        Object[] params = term == null ? new Object[0] : new Object[]{term};
        return support.page("""
            select u.id, u.email, u.email as name, u.status,
                   coalesce((select string_agg(r.code, ',' order by r.code)
                       from identity.user_roles ur join identity.roles r on r.id = ur.role_id
                       where ur.user_id = u.id), '') as roles,
                   sp.display_name as "sellerDisplayName", sp.status as "sellerStatus"
            from identity.users u
            left join seller.seller_profiles sp on sp.user_id = u.id
            """ + where + " order by lower(u.email)",
            "select count(*) from identity.users u" + where, page, size, params);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size) {
        return support.page("""
            select sp.id, sp.display_name as "displayName", u.email
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            order by sp.display_name
            """, "select count(*) from seller.seller_profiles sp join identity.users u on u.id = sp.user_id",
            page, size);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size, String search, String status) {
        String term = ReadQuerySupport.escapedLike(search);
        String state = status == null ? "" : status.trim();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (term != null) {
            where.append(" and (lower(sp.display_name) like ? escape '!' or lower(u.email) like ? escape '!')");
            params.add(term);
            params.add(term);
        }
        if (!state.isEmpty()) {
            where.append(" and sp.status = ?");
            params.add(state);
        }
        return support.page("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            """ + where + """
             order by sp.display_name
            """, """
            select count(*)
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            """ + where, page, size, params.toArray());
    }

    public PageResponse<Map<String, Object>> commercialProfiles(
            int page, int size, String search, String status, String kind, boolean canViewResellers) {
        String safeKind = kind == null ? "" : kind.trim();
        String safeStatus = status == null ? "" : status.trim();
        if (!safeKind.isEmpty() && !"SELLER".equals(safeKind) && !"RESELLER".equals(safeKind)) {
            throw new IllegalArgumentException("El tipo de perfil debe ser SELLER o RESELLER");
        }
        String term = ReadQuerySupport.escapedLike(search);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(canViewResellers);
        if (term != null) {
            where.append(" and (lower(profile.display_name) like ? escape '!' or lower(profile.email) like ? escape '!')");
            params.add(term);
            params.add(term);
        }
        if (!safeStatus.isEmpty()) {
            where.append(" and profile.status = ?");
            params.add(safeStatus);
        }
        if (!safeKind.isEmpty()) {
            where.append(" and profile.kind = ?");
            params.add(safeKind);
        }
        String profiles = """
            from (
                select sp.id, sp.user_id, sp.display_name, u.email, sp.status, 'SELLER' as kind
                from seller.seller_profiles sp
                join identity.users u on u.id = sp.user_id
                union all
                select c.id, null, c.business_name, null, c.status, 'RESELLER'
                from customer.customers c
                where ? and c.kind = 'RESELLER'
            ) profile
            """ + where;
        return support.page("""
            select profile.id, profile.user_id as "userId", profile.display_name as "displayName",
                   profile.email, profile.status, profile.kind,
                   case when profile.kind = 'RESELLER' then
                       (select count(*) from customer.customers c where c.reseller_id = profile.id)
                   else
                       (select count(*) from customer.customers c where c.seller_id = profile.id)
                   end as "assignedCustomersCount"
            """ + profiles + " order by lower(profile.display_name), profile.kind, profile.id",
            "select count(*) " + profiles, page, size, params.toArray());
    }

    public Map<String, Object> sellerDetail(UUID id) {
        return support.jdbc().queryForMap("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where sp.id = ?
            """, id);
    }
}
