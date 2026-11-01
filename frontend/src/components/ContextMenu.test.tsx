// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ContextMenu } from './ContextMenu'
import type { MenuEntry } from './ContextMenu'
import { OcrFixBar } from './OcrFixBar'

afterEach(cleanup)

describe('ContextMenu', () => {
  const entries = (copy: () => void, remove: () => void): MenuEntry[] => [
    { label: '복사', shortcut: 'Ctrl+C', onSelect: copy },
    { label: '붙여넣기', disabled: true, onSelect: () => {} },
    'separator',
    { label: '삭제', shortcut: 'Delete', onSelect: remove },
  ]

  it('does what the item says and closes, and shows the shortcut to learn', () => {
    const copy = vi.fn()
    const onClose = vi.fn()
    render(<ContextMenu x={10} y={10} label="줄" entries={entries(copy, () => {})} onClose={onClose} />)

    expect(screen.getByRole('menu', { name: '줄' })).toBeTruthy()
    expect(screen.getByText('Ctrl+C')).toBeTruthy()
    fireEvent.click(screen.getByRole('menuitem', { name: /복사/ }))

    expect(copy).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalled()
  })

  it('is worked with the arrow keys, skipping what cannot be done, and closes with Esc', () => {
    const onClose = vi.fn()
    render(<ContextMenu x={10} y={10} label="줄" entries={entries(() => {}, () => {})} onClose={onClose} />)
    const menu = screen.getByRole('menu')

    expect(document.activeElement?.textContent).toContain('복사')
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toContain('삭제')
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('closes on a click elsewhere', () => {
    const onClose = vi.fn()
    render(<ContextMenu x={10} y={10} label="쪽" entries={entries(() => {}, () => {})} onClose={onClose} />)

    fireEvent.pointerDown(document.body)

    expect(onClose).toHaveBeenCalled()
  })
})

describe('OcrFixBar', () => {
  const lines = [
    { text: '진료비 계산서', x: 0, y: 0, width: 100, height: 10, score: 0.99 },
    { text: '요.양급여', x: 0, y: 20, width: 100, height: 10, score: 0.7 },
    { text: '공단', x: 0, y: 40, width: 100, height: 10, score: 0.95 },
    { text: '비급여④', x: 0, y: 60, width: 100, height: 10, score: 0.6 },
  ]

  it('saves the corrected text with Enter and goes on to the next line to check', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const onGo = vi.fn()
    render(<OcrFixBar lines={lines} index={1} unsure={0.9} onSave={onSave} onGo={onGo} onClose={() => {}} />)
    const input = screen.getByRole('textbox', { name: '이 줄의 글자' }) as HTMLInputElement

    expect(input.value).toBe('요.양급여')
    expect(screen.getByText(/확인할 줄 2개/)).toBeTruthy()
    fireEvent.change(input, { target: { value: '요양급여' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await vi.waitFor(() => expect(onGo).toHaveBeenCalledWith(3))
    expect(onSave).toHaveBeenCalledWith(1, '요양급여')
  })

  it('steps between the lines to check and closes after the last one', async () => {
    const onGo = vi.fn()
    const onClose = vi.fn()
    render(
      <OcrFixBar lines={lines} index={3} unsure={0.9} onSave={vi.fn().mockResolvedValue(undefined)} onGo={onGo} onClose={onClose} />,
    )

    expect((screen.getByRole('button', { name: /다음/ }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: /이전/ }))
    expect(onGo).toHaveBeenCalledWith(1)
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await vi.waitFor(() => expect(onClose).toHaveBeenCalled())
  })
})
