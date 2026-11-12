import { createUniver, defaultTheme, LocaleType, mergeLocales } from '@univerjs/presets'
import type { IWorkbookData } from '@univerjs/presets'
import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core'
import CoreKo from '@univerjs/preset-sheets-core/locales/ko-KR'
import '@univerjs/preset-sheets-core/lib/index.css'
import type { Book } from '../sheetEdit'

/**
 * 표 다듬기: the spreadsheet screen (Univer, Apache-2.0) the app shows in a frame of its own, so its keys and its
 * styles stay apart from the app's. The app hands it a workbook (see sheetEdit) through `window.polish` and takes
 * the workbook back when it is saved. Nothing here talks to a server.
 */
export interface PolishApi {
  /** Shows this workbook in place of the one shown; done once it is drawn and takes the keys. */
  load(book: Book): Promise<void>
  /** The workbook as it is now. */
  save(): Book
  /** Whether anything was changed since it was loaded or last marked saved. */
  changed(): boolean
  saved(): void
}

const { univerAPI: api } = createUniver({
  theme: defaultTheme,
  locale: LocaleType.KO_KR,
  locales: { [LocaleType.KO_KR]: mergeLocales(CoreKo) },
  presets: [UniverSheetsCorePreset({ container: 'sheet' })],
})

let changed = false
let loading = false
/** The spreadsheet is up and drawn (its "steady" stage); at the latest a few seconds after it started. */
const steady = new Promise<void>((resolve) => {
  const listener = api.addEvent(api.Event.LifeCycleChanged, (event) => {
    if (event.stage >= 3) {
      listener.dispose()
      resolve()
    }
  })
  setTimeout(resolve, 8000)
})
// A mutation (type 2) that is not the formula engine working: something the user did.
api.addEvent(api.Event.CommandExecuted, (event) => {
  if (!loading && event.type === 2 && !event.id.includes('formula') && !event.id.includes('selection')) changed = true
})

const polish: PolishApi = {
  load(book) {
    loading = true
    const shown = api.getActiveWorkbook()
    if (shown) api.disposeUnit(shown.getId())
    api.createWorkbook({ ...(book as unknown as IWorkbookData), locale: LocaleType.KO_KR })
    // The first drawing of a workbook runs commands of its own; they are not the user's changes.
    return steady.then(
      () =>
        new Promise<void>((resolve) =>
          setTimeout(() => {
            loading = false
            changed = false
            resolve()
          }, 600),
        ),
    )
  },
  save: () => api.getActiveWorkbook()!.save() as unknown as Book,
  changed: () => changed,
  saved: () => {
    changed = false
  },
}

declare global {
  interface Window {
    polish?: PolishApi
  }
}
window.polish = polish
