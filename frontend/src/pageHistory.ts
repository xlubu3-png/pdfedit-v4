import { useCallback, useState } from 'react'
import type { PageItem } from './types'

/** How many changes of the page list Ctrl+Z can take back. */
export const HISTORY_LIMIT = 50

/** The page list with the lists it was before each change (to undo) and after each undone one (to redo). */
export interface PageListState {
  pages: PageItem[]
  undo: PageItem[][]
  redo: PageItem[][]
}

export type PagesUpdate = PageItem[] | ((prev: PageItem[]) => PageItem[])

const apply = (update: PagesUpdate, prev: PageItem[]) => (typeof update === 'function' ? update(prev) : update)

/** A change the user made (pages added, removed, moved, turned): one step Ctrl+Z takes back. */
export function changed(state: PageListState, update: PagesUpdate): PageListState {
  const next = apply(update, state.pages)
  if (next === state.pages) return state
  return { pages: next, undo: [...state.undo, state.pages].slice(-HISTORY_LIMIT), redo: [] }
}

/** A change that is no step of its own: a page drawn again after an edit, its recognized lines, the restored list. */
export function updated(state: PageListState, update: PagesUpdate): PageListState {
  const next = apply(update, state.pages)
  return next === state.pages ? state : { ...state, pages: next }
}

export function undone(state: PageListState): PageListState {
  const before = state.undo.at(-1)
  if (!before) return state
  return { pages: refreshed(before, state.pages), undo: state.undo.slice(0, -1), redo: [...state.redo, state.pages] }
}

export function redone(state: PageListState): PageListState {
  const after = state.redo.at(-1)
  if (!after) return state
  return { pages: refreshed(after, state.pages), undo: [...state.undo, state.pages], redo: state.redo.slice(0, -1) }
}

/**
 * A list from the history, its pages as they are now: a step back is about the list - which pages, in what
 * order, turned how - not about what was learned of a page since (its text edits, its recognized lines).
 */
function refreshed(list: PageItem[], now: PageItem[]): PageItem[] {
  const latest = new Map(now.map((p) => [p.key, p]))
  return list.map((p) => {
    const current = latest.get(p.key)
    return current ? { ...current, rotation: p.rotation } : p
  })
}

/** The page list of the first screen, with undo and redo of the changes the user makes to it. */
export function usePageList() {
  const [state, setState] = useState<PageListState>({ pages: [], undo: [], redo: [] })
  const setPages = useCallback((update: PagesUpdate) => setState((s) => updated(s, update)), [])
  const changePages = useCallback((update: PagesUpdate) => setState((s) => changed(s, update)), [])
  const undo = useCallback(() => setState(undone), [])
  const redo = useCallback(() => setState(redone), [])
  const forget = useCallback(() => setState((s) => ({ ...s, undo: [], redo: [] })), [])
  return {
    pages: state.pages,
    setPages,
    changePages,
    undo,
    redo,
    forget,
    canUndo: state.undo.length > 0,
    canRedo: state.redo.length > 0,
  }
}
