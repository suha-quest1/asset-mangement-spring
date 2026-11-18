// Package report renders the regulatory liquidity report as JSON and PDF.
// The PDF writer is a tiny, dependency-free PDF 1.4 generator (Helvetica /
// Courier text only) — sufficient for a tabular regulatory-style report.
package report

import (
	"bytes"
	"fmt"
	"strings"
)

type line struct {
	text string
	font string // F1 Helvetica, F2 Helvetica-Bold, F3 Courier
	size float64
	gap  float64 // extra space before this line
}

// PDF accumulates lines and paginates them on A4.
type PDF struct{ lines []line }

func (p *PDF) Title(s string)   { p.lines = append(p.lines, line{s, "F2", 16, 6}) }
func (p *PDF) Heading(s string) { p.lines = append(p.lines, line{s, "F2", 11, 10}) }
func (p *PDF) Text(s string)    { p.lines = append(p.lines, line{s, "F1", 9.5, 0}) }
func (p *PDF) Mono(s string)    { p.lines = append(p.lines, line{s, "F3", 8.5, 0}) }

func esc(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `(`, `\(`, `)`, `\)`)
	// keep to WinAnsi-safe ASCII
	b := make([]rune, 0, len(s))
	for _, c := range s {
		if c > 126 {
			switch c {
			case '—', '–':
				c = '-'
			case '≥':
				c = '>'
			default:
				c = '?'
			}
		}
		b = append(b, c)
	}
	return r.Replace(string(b))
}

// Bytes renders the document.
func (p *PDF) Bytes() []byte {
	const (
		pageW, pageH = 595.0, 842.0
		marginL, top = 50.0, 790.0
		bottom       = 60.0
	)
	// paginate
	var pages []string
	var cur strings.Builder
	y := top
	pageNo := 1
	flush := func() {
		cur.WriteString(fmt.Sprintf("BT /F1 8 Tf %.1f %.1f Td (Page %d - Intelligent Capital Call & Liquidity Orchestration - Camunda 8) Tj ET\n", marginL, 30.0, pageNo))
		pages = append(pages, cur.String())
		cur.Reset()
		y = top
		pageNo++
	}
	for _, l := range p.lines {
		h := l.size*1.35 + l.gap
		if y-h < bottom {
			flush()
		}
		y -= h
		cur.WriteString(fmt.Sprintf("BT /%s %.1f Tf %.1f %.1f Td (%s) Tj ET\n", l.font, l.size, marginL, y, esc(l.text)))
	}
	flush()

	var out bytes.Buffer
	var offsets []int
	obj := func(body string) {
		offsets = append(offsets, out.Len())
		out.WriteString(fmt.Sprintf("%d 0 obj\n%s\nendobj\n", len(offsets), body))
	}
	out.WriteString("%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
	n := len(pages)
	// 1 catalog, 2 pages, 3-5 fonts, then per page: page obj + content obj
	obj("<< /Type /Catalog /Pages 2 0 R >>")
	kids := make([]string, n)
	for i := range pages {
		kids[i] = fmt.Sprintf("%d 0 R", 6+i*2)
	}
	obj(fmt.Sprintf("<< /Type /Pages /Kids [%s] /Count %d >>", strings.Join(kids, " "), n))
	obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
	obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
	obj("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
	for i, content := range pages {
		obj(fmt.Sprintf("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents %d 0 R >>", pageW, pageH, 7+i*2))
		obj(fmt.Sprintf("<< /Length %d >>\nstream\n%sendstream", len(content), content))
	}
	xref := out.Len()
	out.WriteString(fmt.Sprintf("xref\n0 %d\n0000000000 65535 f \n", len(offsets)+1))
	for _, o := range offsets {
		out.WriteString(fmt.Sprintf("%010d 00000 n \n", o))
	}
	out.WriteString(fmt.Sprintf("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(offsets)+1, xref))
	return out.Bytes()
}
