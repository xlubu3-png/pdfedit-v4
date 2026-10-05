// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AddedText, PageText, TextRun } from '../types'
import { AddedBox, RunBox } from './TextBoxes'

afterEach(cleanup)

const run: TextRun = {
  index: 0,
  text: '시행규칙',
  currentText: '시행규칙',
  edited: false,
  x: 72,
  y: 700,
  width: 80,
  height: 12,
  fontSize: 12,
  boxBottom: 697,
  boxTop: 713,
  fontFamily: '맑은 고딕',
  bold: false,
  sourceFont: 'Helvetica',
  color: '#000000',
  glyphReuse: true,
  ownChars: '',
  sourceFontMissing: false,
  editFontFamily: null,
  editFontSize: null,
  editBold: null,
  editColor: null,
  dx: 0,
  dy: 0,
}

const data: PageText = {
  pageWidth: 595,
  pageHeight: 842,
  originX: 0,
  originY: 0,
  rotated: false,
  runs: [run],
  added: [],
}

const targets = { xs: [], ys: [] }
const noop = () => {}

function renderRun(editing: boolean, onCommitText = vi.fn(), onStopEdit = vi.fn()) {
  const props = {
    run,
    state: undefined,
    scale: 1.5,
    data,
    selected: true,
    targets,
    onSelect: noop,
    onStartEdit: noop,
    onStopEdit,
    onCommitText,
    onMoveTo: noop,
    onGuides: noop,
  }
  const view = render(<RunBox {...props} editing={editing} />)
  const box = () => view.container.querySelector<HTMLElement>('[data-index]')!
  return { view, box, rerender: (next: boolean) => view.rerender(<RunBox {...props} editing={next} />) }
}

describe('RunBox', () => {
  it('keeps the typed text when it is left in the usual way (focus leaves while editing)', () => {
    const onCommitText = vi.fn()
    const { box } = renderRun(true, onCommitText)

    box().textContent = '시행규칙칙'
    fireEvent.blur(box())

    expect(onCommitText).toHaveBeenCalledWith(run, '시행규칙칙')
  })

  it('keeps the typed text when a click on the page turns editing off before the focus leaves', () => {
    // Clicking an empty part of the page switches "editing" off first; the browser's blur comes after.
    // That blur used to be ignored, so the edit was lost until the box was entered and left again.
    const onCommitText = vi.fn()
    const onStopEdit = vi.fn()
    const { box, rerender } = renderRun(true, onCommitText, onStopEdit)

    box().textContent = '시행규칙칙'
    rerender(false)
    fireEvent.blur(box())

    expect(onCommitText).toHaveBeenCalledWith(run, '시행규칙칙')
  })

  it('is not editable until editing starts', () => {
    const { box, rerender } = renderRun(false)

    expect(box().getAttribute('contenteditable')).toBe('false')
    rerender(true)
    expect(box().getAttribute('contenteditable')).toBe('true')
  })
})

const added: AddedText = { id: 'a1', text: '새 글자', x: 100, y: 400, fontFamily: null, fontSize: 12, bold: false, color: '#000000' }

describe('AddedBox', () => {
  it('keeps the typed text when editing is switched off before the focus leaves', () => {
    const onCommitText = vi.fn()
    const props = {
      box: added,
      scale: 1.5,
      data,
      selected: true,
      targets,
      onSelect: noop,
      onStartEdit: noop,
      onStopEdit: noop,
      onCommitText,
      onMoveTo: noop,
      onRemove: noop,
      onGuides: noop,
    }
    const view = render(<AddedBox {...props} editing={true} />)
    const box = view.container.querySelector<HTMLElement>('[data-added]')!

    box.textContent = '새 글자 더'
    view.rerender(<AddedBox {...props} editing={false} />)
    fireEvent.blur(box)

    expect(onCommitText).toHaveBeenCalledWith(added, '새 글자 더', null)
  })
})
