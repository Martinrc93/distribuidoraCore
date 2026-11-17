export type Permission = { code: string; description: string; adminOnly: boolean }

export function RolePermissionFields({ permissions, selected, required = [], isAdminRole = false, disabled = false, onChange }: {
  permissions: Permission[]; selected: string[]; required?: string[]; isAdminRole?: boolean; disabled?: boolean; onChange: (codes: string[]) => void
}) {
  return <fieldset className="role-permissions-grid" disabled={disabled}>
    <legend className="sr-only">Permisos disponibles</legend>
    {permissions.map(permission => {
      const mandatory = required.includes(permission.code)
      const restricted = permission.adminOnly && !isAdminRole
      return <label className="role-permission-option" key={permission.code} aria-disabled={mandatory || restricted}>
        <input type="checkbox" checked={selected.includes(permission.code)} disabled={mandatory || restricted}
          aria-describedby={`permission-help-${permission.code}`} onChange={event => onChange(event.target.checked ? [...selected, permission.code] : selected.filter(code => code !== permission.code))} />
        <span><span className="role-permission-name">{permission.description || permission.code}</span>
          <span id={`permission-help-${permission.code}`} className="role-permission-help">{mandatory ? 'Obligatorio para este rol.' : restricted ? 'Reservado al rol Administrador.' : permission.adminOnly ? 'Solo administradores.' : 'Disponible para este rol.'}</span>
          {permission.code === 'ORDER_PREPARE' && <span className="role-permission-help">Preparar antes de entregar. Entregar un pedido pendiente también prepara y descuenta stock.</span>}
        </span>
      </label>
    })}
  </fieldset>
}
