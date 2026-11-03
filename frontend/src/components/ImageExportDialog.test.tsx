// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ImageExportDialog } from './ImageExportDialog'

afterEach(cleanup)

function renderDialog(pageCount = 3, fromProtected = false) {
  const onExport = vi.fn()
  const onCancel = vi.fn()
  render(
    <ImageExportDialog
      pageCount={pageCount}
      picked={false}
      fromProtected={fromProtected}
      initial={{ format: 'jpg', dpi: 200 }}
      onExport={onExport}
      onCancel={onCancel}
    />,
  )
  return { onExport, onCancel }
}

describe('ImageExportDialog', () => {
  it('saves with the kind and sharpness picked', () => {
    const { onExport } = renderDialog()

    fireEvent.click(screen.getByRole('radio', { name: /PNG/ }))
    fireEvent.click(screen.getByRole('radio', { name: /아주 선명하게/ }))
    fireEvent.click(screen.getByRole('button', { name: '사진으로 저장' }))

    expect(onExport).toHaveBeenCalledWith({ format: 'png', dpi: 300 })
  })

  it('says several pages come as one ZIP file, and one page as one picture', () => {
    renderDialog(3)
    expect(screen.getByRole('dialog').textContent).toContain('ZIP')
    cleanup()
    renderDialog(1)
    expect(screen.getByRole('dialog').textContent).toContain('사진 한 장')
  })

  it('warns that a picture of a protected page has no password, and Esc gives up', () => {
    const { onExport, onCancel } = renderDialog(1, true)

    expect(screen.getByRole('dialog').textContent).toContain('암호를 걸 수 없습니다')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })

    expect(onCancel).toHaveBeenCalled()
    expect(onExport).not.toHaveBeenCalled()
  })
})
