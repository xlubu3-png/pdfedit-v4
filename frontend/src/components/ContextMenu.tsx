import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

/** One thing the menu offers: what it says, its shortcut to learn, and what it does. */
export interface MenuItem {
  label: string
  shortcut?: string
  disabled?: boolean
  onSelect: () => void
}

/** A menu entry, or a line between groups of them. */
export type MenuEntry = MenuItem | 'separator'

interface ContextMenuProps {
  /** Where it was asked for, pixels of the window. */
  x: number
  y: number
  entries: MenuEntry[]
  label: string
  /** A line of explanation under the items. */
  note?: string
  onClose: () => void
}

/**
 * The menu a right click (or the menu key, Shift+F10) opens: kept inside the window, worked with the arrow keys
 * and Enter, and closed by Esc, a click elsewhere, scrolling or leaving the window.
 */
export function ContextMenu({ x, y, entries, label, note, onClose }: ContextMenuProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [place, setPlace] = useState({ left: x, top: y })

  // Kept inside the window: opened near its right or bottom edge it opens leftwards or upwards.
  useLayoutEffect(() => {
    const menu = ref.current
    if (!menu) return
    const { width, height } = menu.getBoundingClientRect()
    setPlace({
      left: Math.max(4, Math.min(x, window.innerWidth - width - 4)),
      top: Math.max(4, Math.min(y, window.innerHeight - height - 4)),
    })
    menu.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  }, [x, y])

  useEffect(() => {
    const away = (e: Event) => {
      if (!(e.target instanceof Node) || !ref.current?.contains(e.target)) onClose()
    }
    const leave = () => onClose()
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('wheel', leave, true)
    window.addEventListener('blur', leave)
    window.addEventListener('resize', leave)
    return () => {
      document.removeEventListener('pointerdown', away, true)
      document.removeEventListener('wheel', leave, true)
      window.removeEventListener('blur', leave)
      window.removeEventListener('resize', leave)
    }
  }, [onClose])

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const buttons = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
    if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      onClose()
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      e.stopPropagation()
      const step = e.key === 'ArrowDown' ? 1 : -1
      buttons[(at + step + buttons.length) % buttons.length]?.focus()
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault()
      buttons[e.key === 'Home' ? 0 : buttons.length - 1]?.focus()
    } else {
      // The editor's own keys wait while the menu is open (Enter and Space press the focused item).
      e.stopPropagation()
    }
  }

  return (
    <div
      ref={ref}
      className="context-menu"
      role="menu"
      aria-label={label}
      style={{ left: place.left, top: place.top }}
      onKeyDown={onKeyDown}
      onContextMenu={(e) => e.preventDefault()}
    >
      {entries.map((entry, i) =>
        entry === 'separator' ? (
          <div key={`s${i}`} className="context-menu-separator" role="separator" />
        ) : (
          <button
            key={entry.label}
            type="button"
            role="menuitem"
            disabled={entry.disabled}
            onClick={() => {
              onClose()
              entry.onSelect()
            }}
          >
            <span>{entry.label}</span>
            {entry.shortcut && <kbd>{entry.shortcut}</kbd>}
          </button>
        ),
      )}
      {note && (
        <p className="context-menu-note" role="note">
          {note}
        </p>
      )}
    </div>
  )
}
