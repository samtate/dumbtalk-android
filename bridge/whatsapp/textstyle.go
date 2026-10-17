// Message text as shown: mentions written as names and WhatsApp's
// formatting markers (*bold*, _italic_, ~strike~, ```mono```, `code`)
// turned into styled spans. Offsets are UTF-16 units, as Kotlin counts.

package wabridge

import (
	"strings"
	"unicode"
	"unicode/utf16"
)

// span styles part of a message's text; ID is the mentioned JID.
type span struct {
	Start  int    `json:"start"`
	Length int    `json:"length"`
	Style  string `json:"style"`
	ID     string `json:"id,omitempty"`
}

// richText is text being edited rune by rune, with spans over it.
type richText struct {
	runes []rune
	spans []span // rune offsets until utf16Spans
}

// cut removes runes [at, at+n), moving and shrinking spans to match.
func (t *richText) cut(at, n int) {
	t.runes = append(t.runes[:at], t.runes[at+n:]...)
	for i := range t.spans {
		s := &t.spans[i]
		end := s.Start + s.Length
		switch {
		case s.Start >= at+n:
			s.Start -= n
		case end <= at:
		default:
			overlap := min(end, at+n) - max(s.Start, at)
			s.Length -= overlap
			if s.Start > at {
				s.Start = at
			}
		}
	}
}

// replace puts with in place of runes [at, at+n).
func (t *richText) replace(at, n int, with []rune) {
	grow := len(with) - n
	tail := append([]rune{}, t.runes[at+n:]...)
	t.runes = append(append(t.runes[:at], with...), tail...)
	for i := range t.spans {
		if t.spans[i].Start >= at+n {
			t.spans[i].Start += grow
		}
	}
}

// inside reports whether rune i lies in a span of one of styles.
func (t *richText) inside(i int, styles ...string) bool {
	for _, s := range t.spans {
		if i >= s.Start && i < s.Start+s.Length {
			for _, style := range styles {
				if s.Style == style {
					return true
				}
			}
		}
	}
	return false
}

func boundary(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }

// format finds marker pairs (marker, text, marker) on one line, with
// the text not starting or ending in a space and the markers at word
// edges, and replaces each with a span.
func (t *richText) format(marker string, style string) {
	m := []rune(marker)
	width := len(m)
	at := func(i int) bool {
		if i < 0 || i+width > len(t.runes) {
			return false
		}
		for k, r := range m {
			if t.runes[i+k] != r {
				return false
			}
		}
		return !t.inside(i, "mention", "mono")
	}
	for i := 0; i < len(t.runes); i++ {
		if !at(i) || (i > 0 && !boundary(t.runes[i-1])) {
			continue
		}
		first := i + width
		if first >= len(t.runes) || unicode.IsSpace(t.runes[first]) {
			continue
		}
		multiline := style == "mono" && width == 3
		for j := first + 1; j+width <= len(t.runes); j++ {
			if t.runes[j-1] == '\n' && !multiline {
				break
			}
			if !at(j) || unicode.IsSpace(t.runes[j-1]) {
				continue
			}
			if after := j + width; after < len(t.runes) && !boundary(t.runes[after]) {
				continue
			}
			t.cut(j, width)
			t.cut(i, width)
			t.spans = append(t.spans, span{Start: i, Length: j - first, Style: style})
			i = j - width - 1 // continue after the closing marker
			break
		}
	}
}

// renderText applies mentions (by the JIDs' user parts, "@447700900123")
// and formatting. name gives each mentioned JID's display name.
func renderText(text string, mentions []string, name func(string) string) (string, []span) {
	t := &richText{runes: []rune(text)}
	for _, jid := range mentions {
		user, _, _ := strings.Cut(jid, "@")
		if user == "" {
			continue
		}
		tag := []rune("@" + user)
		shown := []rune("@" + name(jid))
		for i := 0; i+len(tag) <= len(t.runes); i++ {
			if string(t.runes[i:i+len(tag)]) != string(tag) || t.inside(i, "mention") {
				continue
			}
			if end := i + len(tag); end < len(t.runes) && unicode.IsDigit(t.runes[end]) {
				continue // a longer number
			}
			t.replace(i, len(tag), shown)
			t.spans = append(t.spans, span{Start: i, Length: len(shown), Style: "mention", ID: jid})
			i += len(shown) - 1
		}
	}
	t.format("```", "mono")
	t.format("`", "mono")
	t.format("*", "bold")
	t.format("_", "italic")
	t.format("~", "strike")
	return string(t.runes), t.utf16Spans()
}

// utf16Spans converts the spans' rune offsets to UTF-16 units.
func (t *richText) utf16Spans() []span {
	if len(t.spans) == 0 {
		return nil
	}
	offsets := make([]int, len(t.runes)+1)
	for i, r := range t.runes {
		offsets[i+1] = offsets[i] + len(utf16.Encode([]rune{r}))
	}
	out := make([]span, 0, len(t.spans))
	for _, s := range t.spans {
		if s.Length <= 0 {
			continue
		}
		end := min(s.Start+s.Length, len(t.runes))
		out = append(out, span{Start: offsets[s.Start], Length: offsets[end] - offsets[s.Start], Style: s.Style, ID: s.ID})
	}
	return out
}

// encodeMentions is the reverse for sending: each mention span (UTF-16,
// over "@Name") becomes "@<user>", and the JIDs are listed for ContextInfo.
func encodeMentions(text string, spans []span) (string, []string) {
	units := utf16.Encode([]rune(text))
	var jids []string
	// Right to left, so earlier offsets stay valid.
	ordered := append([]span{}, spans...)
	for i := 1; i < len(ordered); i++ {
		for j := i; j > 0 && ordered[j].Start > ordered[j-1].Start; j-- {
			ordered[j], ordered[j-1] = ordered[j-1], ordered[j]
		}
	}
	for _, s := range ordered {
		if s.Style != "mention" || s.ID == "" || s.Start < 0 || s.Start+s.Length > len(units) {
			continue
		}
		user, _, _ := strings.Cut(s.ID, "@")
		tag := utf16.Encode([]rune("@" + user))
		units = append(units[:s.Start], append(tag, units[s.Start+s.Length:]...)...)
		jids = append([]string{s.ID}, jids...)
	}
	return string(utf16.Decode(units)), jids
}
