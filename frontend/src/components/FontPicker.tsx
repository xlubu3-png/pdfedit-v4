import { useEffect, useMemo, useRef, useState } from 'react'
import type { FontChoice } from '../types'

const RECENT_KEY = 'wintech-pdf-recent-fonts'
const RECENT_COUNT = 5

function loadRecent(): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((n): n is string => typeof n === 'string') : []
  } catch {
    return []
  }
}

function rememberFont(name: string) {
  try {
    const next = [name, ...loadRecent().filter((n) => n !== name)].slice(0, RECENT_COUNT)
    localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    // private mode: recent fonts are simply not remembered
  }
}

interface FontPickerProps {
  fonts: FontChoice[]
  /** The chosen family, or null for the automatic one. */
  value: string | null
  /** What "no choice" is called, e.g. "자동 (맑은 고딕)". */
  autoLabel: string
  onChange: (family: string | null) => void
  disabled?: boolean
}

/**
 * A font list you can search and that shows each font in its own face, with the fonts used last on
 * top. A plain <select> of a hundred families is hard to scroll and shows none of them.
 */
export function FontPicker({ fonts, value, autoLabel, onChange, disabled }: FontPickerProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [recent, setRecent] = useState<string[]>([])
  const root = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const current = fonts.find((f) => f.name === value)
  const needle = query.trim().toLowerCase()
  const filtered = useMemo(
    () => (needle ? fonts.filter((f) => f.label.toLowerCase().includes(needle) || f.name.toLowerCase().includes(needle)) : fonts),
    [fonts, needle],
  )
  const recentFonts = useMemo(
    () => recent.map((n) => fonts.find((f) => f.name === n)).filter((f): f is FontChoice => f !== undefined),
    [recent, fonts],
  )

  function openPicker() {
    setRecent(loadRecent())
    setQuery('')
    setOpen(true)
  }

  function pick(name: string | null) {
    if (name !== null) rememberFont(name)
    onChange(name)
    setOpen(false)
  }

  function item(f: FontChoice) {
    return (
      <li key={f.name}>
        <button
          type="button"
          role="option"
          aria-selected={f.name === value}
          className={f.name === value ? 'on' : ''}
          style={{ fontFamily: `'${f.name}', sans-serif` }}
          onClick={() => pick(f.name)}
        >
          {f.label}
        </button>
      </li>
    )
  }

  return (
    <div className="fp" ref={root}>
      <button
        type="button"
        className="fp-button"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => (open ? setOpen(false) : openPicker())}
      >
        <span style={{ fontFamily: current ? `'${current.name}', sans-serif` : undefined }}>
          {current ? current.label : autoLabel}
        </span>
        <span aria-hidden="true"> ▾</span>
      </button>
      {open && (
        <div className="fp-popup">
          <input
            className="fp-search"
            type="search"
            placeholder="글꼴 검색…"
            value={query}
            ref={(el) => el?.focus()}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.stopPropagation()
                setOpen(false)
              } else if (e.key === 'Enter' && needle && filtered.length > 0) {
                pick(filtered[0].name)
              }
            }}
          />
          <ul role="listbox" className="fp-list">
            {!needle && (
              <li>
                <button type="button" role="option" aria-selected={value === null} className={value === null ? 'on' : ''} onClick={() => pick(null)}>
                  {autoLabel}
                </button>
              </li>
            )}
            {!needle && recentFonts.length > 0 && (
              <>
                <li className="fp-group">최근 사용</li>
                {recentFonts.map(item)}
              </>
            )}
            {needle ? (
              filtered.length === 0 ? (
                <li className="fp-empty">검색 결과가 없습니다</li>
              ) : (
                filtered.map(item)
              )
            ) : (
              <>
                <li className="fp-group">한글 글꼴</li>
                {filtered.filter((f) => f.korean).map(item)}
                <li className="fp-group">그 외 글꼴</li>
                {filtered.filter((f) => !f.korean).map(item)}
              </>
            )}
          </ul>
        </div>
      )}
    </div>
  )
}
