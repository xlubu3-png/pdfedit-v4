import type { PageItem } from './types'

/** The page list's pages by the file they came from, in the order the files first appear. */
export function filesOf(pages: PageItem[]): { name: string; pages: PageItem[] }[] {
  const groups = new Map<string, PageItem[]>()
  for (const p of pages) {
    if (!groups.has(p.sourceFileName)) groups.set(p.sourceFileName, [])
    groups.get(p.sourceFileName)!.push(p)
  }
  return [...groups].map(([name, filePages]) => ({ name, pages: filePages }))
}

/**
 * The name a file's result is saved under: its own name with the new extension ("견적서.pdf" → "견적서.xlsx"), made
 * unique among those taken (two files called alike get "견적서 (2).pdf").
 */
export function outputName(sourceFileName: string, extension: 'pdf' | 'xlsx', taken: Set<string>): string {
  const stem = sourceFileName.replace(/\.pdf$/i, '').replace(/[\\/:*?"<>|]/g, '_').trim() || '문서'
  let name = `${stem}.${extension}`
  for (let n = 2; taken.has(name.toLowerCase()); n++) name = `${stem} (${n}).${extension}`
  taken.add(name.toLowerCase())
  return name
}

/** The ZIP the results of 파일마다 처리 come in: "일괄처리_20261108_1530.zip". */
export function batchZipName(now: Date): string {
  const two = (n: number) => String(n).padStart(2, '0')
  return `일괄처리_${now.getFullYear()}${two(now.getMonth() + 1)}${two(now.getDate())}_${two(now.getHours())}${two(now.getMinutes())}.zip`
}
