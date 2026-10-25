import type { ReactNode } from 'react'

/** Clicking keeps the focus where it is, so a ribbon button acts on the text being typed or selected. */
const keepFocus = (e: { preventDefault: () => void }) => e.preventDefault()

interface RibbonButtonProps {
  icon: ReactNode
  label: string
  /** The tooltip: what it does, and its shortcut. */
  title?: string
  onClick: () => void
  disabled?: boolean
  /** A toggle that is on. */
  pressed?: boolean
  danger?: boolean
  /** The main action of the ribbon, drawn in the accent colour. */
  primary?: boolean
}

/** A big ribbon button: an icon above a short name, like the ribbons of office programs. */
export function RibbonButton({ icon, label, title, onClick, disabled, pressed, danger, primary }: RibbonButtonProps) {
  const classes = ['rb-button', pressed ? 'on' : '', danger ? 'danger' : '', primary ? 'primary' : ''].filter(Boolean)
  return (
    <button
      type="button"
      className={classes.join(' ')}
      onClick={onClick}
      onMouseDown={keepFocus}
      disabled={disabled}
      title={title}
      aria-pressed={pressed === undefined ? undefined : pressed}
    >
      <span className="rb-icon" aria-hidden="true">
        {icon}
      </span>
      <span className="rb-label">{label}</span>
    </button>
  )
}

/** Buttons that belong together, with their name underneath and a line after them. */
export function RibbonGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rb-group" role="group" aria-label={label}>
      <div className="rb-items">{children}</div>
      <div className="rb-caption">{label}</div>
    </div>
  )
}

export interface RibbonTab {
  id: string
  label: string
}

interface RibbonTabsProps {
  tabs: RibbonTab[]
  active: string
  onChange: (id: string) => void
}

/** The names of the ribbon's pages; the page shown is the one picked. */
export function RibbonTabs({ tabs, active, onChange }: RibbonTabsProps) {
  return (
    <div className="rb-tabs" role="tablist" aria-label="메뉴">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={tab.id === active}
          className={`rb-tab${tab.id === active ? ' active' : ''}`}
          onClick={() => onChange(tab.id)}
          onMouseDown={keepFocus}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}

/** The row of groups of the page shown. */
export function RibbonPanel({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rb-panel" role="toolbar" aria-label={label}>
      {children}
    </div>
  )
}
