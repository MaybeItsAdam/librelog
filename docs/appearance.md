# Appearance

Settings → Appearance lets the user choose a theme, an accent, a typeface and
a text size. The choices are synced settings (`Store::setting` /
`Store::set_setting`), so they follow the user to every device. An unset key
means the default. Apps read them at launch and again after each sync pull,
and apply a change live, with no restart.

| Key | Values | Default |
|---|---|---|
| `appearance.theme` | `system`, `light`, `dark` | `system` |
| `appearance.accent` | `azure`, `emerald`, `raspberry`, `amber`, `purple`, `pink`, `orange` | `azure` |
| `appearance.font` | `arvo`, `sans`, `serif`, `mono` | `arvo` |
| `appearance.text_size` | `small`, `default`, `large`, `larger` (×0.9, ×1, ×1.15, ×1.3) | `default` |

- **Accent** replaces the primary role (actions, links, focus, selection)
  with the hex from SPEC.md §14 / the house palette. Status colours keep
  their fixed meaning (success emerald, danger raspberry, warning amber, info
  azure) whatever the accent is. Amber is light, so text and icons drawn *on*
  an amber fill use the ink colour, not white.
- **Font** sets the reading and writing face: entries, titles and the editor.
  `sans` and `serif` are the platform's own (SF / New York, Roboto / Noto
  Serif, Segoe UI / Cambria). `mono` is Geist Mono. Micro-labels and other
  chrome keep their faces.
- **Text size** scales the reading and writing face on top of the system's own
  Dynamic Type / font scale, never instead of it.
