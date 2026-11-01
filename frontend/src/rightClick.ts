import { useEffect, useLayoutEffect, useRef } from 'react'

/**
 * Right clicks for the app's own menus, taken at the window before anything else on the page sees them.
 *
 * The menus used to listen on their own elements (React's onContextMenu, at the page's root). A browser extension
 * can take the click away on its way there - "allow right click" and mouse-gesture extensions stop the event at the
 * document so that no site can turn the browser's menu off - and then the browser's own menu came up instead of
 * the app's. The window is the first stop of every event, so listening there the app sees the click before them.
 *
 * Should the click not arrive even there (an extension that swallows it outright), the menu is opened anyway once
 * the right button is let go, with a note on why the browser's menu may have come up too.
 */

/**
 * Opens the menu of what is at `target` and returns true (the browser's menu is then turned off), or returns false
 * to leave the click to the browser - in a box being typed in, its menu has paste and spelling. `blocked`: the click
 * reached the app only as a press of the right button; the browser's menu has most likely come up as well.
 */
export type RightClickHandler = (target: Element, x: number, y: number, blocked: boolean) => boolean

/** Shown at the bottom of a menu opened for a click that never reached the app as a right click. */
export const BLOCKED_NOTE =
  '브라우저 메뉴가 같이 떴다면 확장 프로그램(오른쪽 클릭 허용, 마우스 제스처 등)이 오른쪽 클릭을 가로채고 있습니다. 이 앱 주소에서는 그 확장 프로그램을 꺼 주세요.'

/** How long after the right button is let go the right click must have arrived (it comes at once, or before). */
export const WAIT_MS = 300

const handlers = new Set<{ current: RightClickHandler }>()
/** When the last right click (contextmenu event) reached the window, by performance.now(). */
let lastMenuEvent = Number.NEGATIVE_INFINITY
/** The right button's press being watched: where, and when. */
let press: { x: number; y: number; at: number } | null = null
let installed = false

function offer(target: Element, x: number, y: number, blocked: boolean): boolean {
  for (const handler of handlers) {
    if (handler.current(target, x, y, blocked)) return true
  }
  return false
}

function onContextMenu(e: MouseEvent) {
  lastMenuEvent = performance.now()
  if (e.target instanceof Element && offer(e.target, e.clientX, e.clientY, false)) e.preventDefault()
}

function onPointerDown(e: PointerEvent) {
  const mouse = e.pointerType === undefined || e.pointerType === '' || e.pointerType === 'mouse'
  press = e.button === 2 && mouse ? { x: e.clientX, y: e.clientY, at: performance.now() } : null
}

function onPointerUp(e: PointerEvent) {
  const pressed = press
  press = null
  if (!pressed || e.button !== 2) return
  window.setTimeout(() => {
    // The right click came (before the release on Linux and macOS, right after it on Windows): handled there.
    if (lastMenuEvent >= pressed.at) return
    const target = document.elementFromPoint(pressed.x, pressed.y)
    if (target) offer(target, pressed.x, pressed.y, true)
  }, WAIT_MS)
}

function install() {
  if (installed) return
  installed = true
  window.addEventListener('contextmenu', onContextMenu, true)
  window.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('pointerup', onPointerUp, true)
}

/** Offers every right click to `handler` (until the returned function is called). */
export function addRightClickHandler(handler: RightClickHandler): () => void {
  install()
  const entry = { current: handler }
  handlers.add(entry)
  return () => {
    handlers.delete(entry)
  }
}

/** Offers every right click to `handler` - always its latest version - while the component is shown. */
export function useRightClick(handler: RightClickHandler): void {
  const latest = useRef(handler)
  useLayoutEffect(() => {
    latest.current = handler
  })
  useEffect(() => addRightClickHandler((target, x, y, blocked) => latest.current(target, x, y, blocked)), [])
}
