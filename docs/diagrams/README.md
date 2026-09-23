# Diagramas por modulo

Esta carpeta organiza los diagramas manuales por modulo funcional. Cada modulo
tiene un `README.md` con el catalogo recomendado.

Convencion sugerida:

- `flow-<nombre>.md` para procesos y decisiones.
- `sequence-<nombre>.md` para interacciones entre usuario, frontend, API y
  servicios.
- `state-<nombre>.md` para estados y transiciones.
- `er-<nombre>.md` para entidades y relaciones.
- `component-<nombre>.md` para dependencias entre componentes o modulos.

Los diagramas existentes en `docs/architecture` y `docs/domain` sirven como
contexto general. Estos diagramas deben enfocarse en el comportamiento de cada
modulo y pueden escribirse con Mermaid dentro de archivos Markdown.

## Modulos

- [identity](./identity/README.md)
- [seller](./seller/README.md)
- [customer](./customer/README.md)
- [catalog](./catalog/README.md)
- [inventory](./inventory/README.md)
- [order](./order/README.md)
- [sale](./sale/README.md)
- [payment](./payment/README.md)
- [delivery](./delivery/README.md)
- [document](./document/README.md)
- [notification](./notification/README.md)
- [audit](./audit/README.md)
- [administration](./administration/README.md)
- [cross-cutting](./cross-cutting/README.md)
- [future](./future/README.md)
