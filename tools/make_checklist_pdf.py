#!/usr/bin/env python3
"""Makes docs/RELEASE_CHECKLIST.pdf from docs/RELEASE_CHECKLIST.md (run again after the checklist changes).

    python3 tools/make_checklist_pdf.py [input.md] [output.pdf]
"""
import re
import sys
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import Flowable, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle, HRFlowable, KeepTogether

SRC = sys.argv[1] if len(sys.argv) > 1 else "docs/RELEASE_CHECKLIST.md"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/RELEASE_CHECKLIST.pdf"

INK = colors.HexColor("#1b1b1f")
MUTED = colors.HexColor("#5a5a66")
ACCENT = colors.HexColor("#3f3fbf")
RULE = colors.HexColor("#d5d5de")

base = dict(fontName="Helvetica", fontSize=10, leading=14.5, textColor=INK, alignment=TA_LEFT)
S = {
    "h1": ParagraphStyle("h1", **{**base, "fontName": "Helvetica-Bold", "fontSize": 21, "leading": 26, "spaceAfter": 6}),
    "h2": ParagraphStyle("h2", **{**base, "fontName": "Helvetica-Bold", "fontSize": 15, "leading": 19, "spaceBefore": 14, "spaceAfter": 6, "textColor": ACCENT}),
    "h3": ParagraphStyle("h3", **{**base, "fontName": "Helvetica-Bold", "fontSize": 11.5, "leading": 15, "spaceBefore": 10, "spaceAfter": 4}),
    "p": ParagraphStyle("p", **{**base, "spaceAfter": 6}),
    "item": ParagraphStyle("item", **{**base, "leftIndent": 16, "bulletIndent": 0, "spaceAfter": 6, "bulletFontName": "ZapfDingbats", "bulletFontSize": 11}),
    "li": ParagraphStyle("li", **{**base, "leftIndent": 16, "bulletIndent": 4, "spaceAfter": 3}),
    "cell": ParagraphStyle("cell", **{**base, "fontSize": 9.5, "leading": 13}),
    "cellb": ParagraphStyle("cellb", **{**base, "fontSize": 9.5, "leading": 13, "fontName": "Helvetica-Bold"}),
}


def inline(text):
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"`(.+?)`", r'<font face="Courier" size="9">\1</font>', text)
    return text


def build(lines):
    story, i = [], 0
    while i < len(lines):
        line = lines[i].rstrip("\n")
        if not line.strip():
            i += 1
        elif line.startswith("# "):
            story.append(Paragraph(inline(line[2:]), S["h1"]))
            i += 1
        elif line.startswith("## "):
            story.append(Paragraph(inline(line[3:]), S["h2"]))
            i += 1
        elif line.startswith("### "):
            story.append(Paragraph(inline(line[4:]), S["h3"]))
            i += 1
        elif line.strip() == "---":
            story.append(Spacer(1, 4))
            story.append(HRFlowable(width="100%", thickness=0.6, color=RULE))
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            data = [[Paragraph(inline(c), S["cellb"] if r == 0 else S["cell"]) for c in row] for r, row in enumerate(rows)]
            table = Table(data, colWidths=[14 * mm, 62 * mm, 32 * mm, 62 * mm][: len(rows[0])], repeatRows=1)
            table.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#ececf6")),
                ("LINEBELOW", (0, 0), (-1, -1), 0.4, RULE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]))
            story += [table, Spacer(1, 8)]
        else:
            # A list item or a paragraph, with the indented or unbroken lines that follow it.
            box = re.match(r"- \[( |x)\] (.*)", line)
            num = re.match(r"(\d+)\. (.*)", line)
            bullet = re.match(r"- (.*)", line)
            text = (box.group(2) if box else num.group(2) if num else bullet.group(1) if bullet else line)
            i += 1
            while i < len(lines) and lines[i].strip() and (lines[i].startswith("  ") or not (box or num or bullet)) \
                    and not re.match(r"(#|\||- |\d+\. |---)", lines[i]):
                text += " " + lines[i].strip()
                i += 1
            if box:
                story.append(KeepTogether(check_row(inline(text), box.group(1) == "x")))
            elif num:
                story.append(Paragraph(inline(text), S["li"], bulletText=num.group(1) + "."))
            elif bullet:
                story.append(Paragraph(inline(text), S["li"], bulletText="•"))
            else:
                story.append(Paragraph(inline(text), S["p"]))
    return story


class Box(Flowable):
    """An empty square, or one with a tick when done."""

    def __init__(self, done):
        super().__init__()
        self.done, self.width, self.height = done, 9, 9

    def draw(self):
        c = self.canv
        c.setStrokeColor(ACCENT if self.done else MUTED)
        c.setLineWidth(0.9)
        c.rect(0, -1, 8, 8)
        if self.done:
            c.setLineWidth(1.4)
            c.lines([(1.6, 3, 3.4, 1.2), (3.4, 1.2, 6.6, 5.6)])


def check_row(markup, done):
    table = Table([[Box(done), Paragraph(markup, S["p"])]], colWidths=[6 * mm, 168 * mm])
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (0, 0), 3.5), ("TOPPADDING", (1, 0), (1, 0), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 1),
    ]))
    return table


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 10 * mm, "Pixallery release checklist and action plan")
    canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, "Page %d" % doc.page)
    canvas.restoreState()


doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm, bottomMargin=18 * mm,
                        title="Pixallery release checklist and action plan", author="Pixallery")
doc.build(build(open(SRC, encoding="utf-8").read().split("\n")), onFirstPage=footer, onLaterPages=footer)
print("wrote", OUT)
