import { createHash } from 'node:crypto'
import { deflateSync, crc32 } from 'node:zlib'

/** The padding of the PDF standard security handler. */
const PAD = Buffer.from('28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a', 'hex')
const FILE_ID = Buffer.from('0123456789abcdef0123456789abcdef', 'hex')

const md5 = (...parts: Buffer[]) => createHash('md5').update(Buffer.concat(parts)).digest()
const padded = (password: string) => Buffer.concat([Buffer.from(password, 'latin1'), PAD]).subarray(0, 32)

function rc4(key: Buffer, data: Buffer): Buffer {
  const s = Array.from({ length: 256 }, (_, i) => i)
  for (let i = 0, j = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) & 255
    ;[s[i], s[j]] = [s[j], s[i]]
  }
  const out = Buffer.alloc(data.length)
  for (let n = 0, i = 0, j = 0; n < data.length; n++) {
    i = (i + 1) & 255
    j = (j + s[i]) & 255
    ;[s[i], s[j]] = [s[j], s[i]]
    out[n] = data[n] ^ s[(s[i] + s[j]) & 255]
  }
  return out
}

/** Revision 2 (40-bit RC4) of the standard security handler: old, but every reader opens it, and it needs only MD5. */
function security(userPassword: string) {
  const permissions = Buffer.alloc(4)
  permissions.writeInt32LE(-64)
  const owner = rc4(md5(padded('owner')).subarray(0, 5), padded(userPassword))
  const fileKey = md5(padded(userPassword), owner, permissions, FILE_ID).subarray(0, 5)
  return {
    dictionary: `<< /Filter /Standard /V 1 /R 2 /Length 40 /P -64 /O <${owner.toString('hex')}> /U <${rc4(fileKey, PAD).toString('hex')}> >>`,
    encrypt: (objectNumber: number, data: string) => {
      const objectKey = md5(fileKey, Buffer.from([objectNumber & 255, (objectNumber >> 8) & 255, objectNumber >> 16, 0, 0]))
      return rc4(objectKey.subarray(0, 10), Buffer.from(data, 'latin1')).toString('latin1')
    },
  }
}

/**
 * An A4 PDF of `pages` pages, each with a line of Helvetica text per entry, built by hand: nothing to install.
 * With `password` it is encrypted and opens only with that password.
 */
export function simplePdf(lines: string[], pages = 1, password?: string): Buffer {
  const content = lines.map((line, i) => `BT /F1 24 Tf 72 ${700 - i * 50} Td (${line}) Tj ET`).join('\n')
  const secured = password === undefined ? null : security(password)
  const fontId = 3 + pages * 2
  const kids = Array.from({ length: pages }, (_, i) => `${3 + i * 2} 0 R`).join(' ')
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [${kids}] /Count ${pages} >>`,
    ...Array.from({ length: pages }, (_, i) => [
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents ${4 + i * 2} 0 R /Resources << /Font << /F1 ${fontId} 0 R >> >> >>`,
      `<< /Length ${content.length} >>\nstream\n${secured ? secured.encrypt(4 + i * 2, content) : content}\nendstream`,
    ]).flat(),
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    ...(secured ? [secured.dictionary] : []),
  ]
  const encryption = secured ? ` /Encrypt ${objects.length} 0 R /ID [<${FILE_ID.toString('hex')}> <${FILE_ID.toString('hex')}>]` : ''
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((body, i) => {
    offsets.push(out.length)
    out += `${i + 1} 0 obj\n${body}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  out += offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R${encryption} >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

/** An A4 PDF whose one page is nothing but the JPEG picture `jpeg` (`width` x `height`), as a scanner makes it. */
export function scannedPdf(jpeg: Buffer, width: number, height: number): Buffer {
  const draw = 'q 595 0 0 842 0 0 cm /Im1 Do Q'
  const parts: Buffer[] = []
  const offsets: number[] = []
  let length = 0
  const add = (piece: Buffer | string) => {
    const b = typeof piece === 'string' ? Buffer.from(piece, 'latin1') : piece
    parts.push(b)
    length += b.length
  }
  add('%PDF-1.4\n')
  const objects: (string | Buffer[])[] = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> >> >>',
    `<< /Length ${draw.length} >>\nstream\n${draw}\nendstream`,
    [
      Buffer.from(
        `<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} /ColorSpace /DeviceRGB ` +
          `/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`,
        'latin1',
      ),
      jpeg,
      Buffer.from('\nendstream', 'latin1'),
    ],
  ]
  objects.forEach((body, i) => {
    offsets.push(length)
    add(`${i + 1} 0 obj\n`)
    if (typeof body === 'string') add(body)
    else body.forEach(add)
    add('\nendobj\n')
  })
  const xref = length
  add(`xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`)
  add(offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join(''))
  add(`trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`)
  return Buffer.concat(parts)
}

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'latin1'), data])
  const sum = Buffer.alloc(4)
  sum.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, sum])
}

/** A PNG of one flat colour, `width` x `height` pixels. */
export function solidPng(width: number, height: number, [r, g, b]: [number, number, number]): Buffer {
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8 // bits per channel
  header[9] = 2 // truecolour
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: width }, () => [r, g, b]).flat())])
  const raw = Buffer.concat(Array.from({ length: height }, () => row))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/**
 * An A4 PDF with a table drawn as Excel draws one: borders as thin filled bars, each cell's text on its own.
 * `rows` are the cells' texts; a row of fewer cells has its last cell merged over the rest (no borders inside).
 */
export function tablePdf(rows: string[][], widths: number[], left = 60, top = 760, rowHeight = 24): Buffer {
  const xs = widths.reduce((acc, w) => [...acc, acc[acc.length - 1] + w], [left])
  const ops: string[] = []
  const bar = (x: number, y: number, w: number, h: number) => ops.push(`${x} ${y} ${w} ${h} re f`)
  rows.forEach((cells, r) => {
    const y = top - r * rowHeight
    bar(xs[0], y, xs[xs.length - 1] - xs[0], 0.75)
    let x = 0
    cells.forEach((text, c) => {
      const end = c === cells.length - 1 ? xs.length - 1 : x + 1
      bar(xs[x], y - rowHeight, 0.75, rowHeight)
      if (text) ops.push(`BT /F1 10 Tf ${xs[x] + 4} ${y - rowHeight + 8} Td (${text}) Tj ET`)
      x = end
    })
    bar(xs[xs.length - 1], y - rowHeight, 0.75, rowHeight)
  })
  bar(xs[0], top - rows.length * rowHeight, xs[xs.length - 1] - xs[0], 0.75)
  const content = ops.join('\n')
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((body, i) => {
    offsets.push(out.length)
    out += `${i + 1} 0 obj\n${body}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  out += offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}
