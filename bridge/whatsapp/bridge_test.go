package wabridge

import (
	"go.mau.fi/whatsmeow"
	"path/filepath"
	"testing"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestParseKinds(t *testing.T) {
	reply := parseMessage(&waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: proto.String("yes"),
		ContextInfo: &waE2E.ContextInfo{
			StanzaID:      proto.String("Q1"),
			Participant:   proto.String("441234@s.whatsapp.net"),
			QuotedMessage: &waE2E.Message{ImageMessage: &waE2E.ImageMessage{}},
		},
	}})
	if reply.Kind != "text" || reply.Text != "yes" || reply.QuoteID != "Q1" || reply.QuoteText != "Photo" {
		t.Fatalf("reply parsed as %+v", reply)
	}

	reaction := parseMessage(&waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{
		Key: &waCommon.MessageKey{ID: proto.String("M1")}, Text: proto.String("👍"),
	}})
	if !reaction.IsReaction || reaction.ReactionTo != "M1" || reaction.ReactionEmoji != "👍" {
		t.Fatalf("reaction parsed as %+v", reaction)
	}

	revoke := parseMessage(&waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_REVOKE.Enum(), Key: &waCommon.MessageKey{ID: proto.String("M2")},
	}})
	if revoke.RevokeID != "M2" {
		t.Fatalf("revoke parsed as %+v", revoke)
	}

	edit := parseMessage(&waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type:          waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(),
		Key:           &waCommon.MessageKey{ID: proto.String("M3")},
		EditedMessage: &waE2E.Message{Conversation: proto.String("fixed")},
	}})
	if edit.EditID != "M3" || edit.Text != "fixed" {
		t.Fatalf("edit parsed as %+v", edit)
	}

	ephemeral := parseMessage(&waE2E.Message{EphemeralMessage: &waE2E.FutureProofMessage{
		Message: &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{FileName: proto.String("a.pdf")}},
	}})
	if ephemeral.Kind != "document" || ephemeral.Filename != "a.pdf" {
		t.Fatalf("ephemeral document parsed as %+v", ephemeral)
	}

	keyOnly := parseMessage(&waE2E.Message{SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{}})
	if !keyOnly.Ignore {
		t.Fatalf("sender key message should be ignored, got %+v", keyOnly)
	}
}

func TestStoreMessagesAndStatus(t *testing.T) {
	s, err := openStore(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	chat := "441234@s.whatsapp.net"
	for i, id := range []string{"A", "B", "C"} {
		if err := s.putMessage(row{Chat: chat, ID: id, FromMe: true, Ts: int64(1000 + i), Kind: "text", Text: id, Status: "sent"}); err != nil {
			t.Fatal(err)
		}
	}
	// Status only moves forward, and a re-ingest without status keeps it.
	s.setStatus(chat, []string{"B"}, "read")
	s.setStatus(chat, []string{"B"}, "delivered")
	s.putMessage(row{Chat: chat, ID: "B", FromMe: true, Ts: 1001, Kind: "text", Text: "B"})
	if m, _ := s.message(chat, "B"); m.Status != "read" {
		t.Fatalf("status = %q, want read", m.Status)
	}

	s.setReaction(chat, "A", "me", "❤️")
	s.setReaction(chat, "A", "me", "👍")
	s.editMessage(chat, "C", "C2", nil)
	s.deleteMessage(chat, "A")

	page, _ := s.messages(chat, 0, "", 2)
	if len(page) != 2 || page[0].ID != "B" || page[1].Text != "C2" || !page[1].Edited {
		t.Fatalf("newest page = %+v", page)
	}
	older, _ := s.messages(chat, page[0].Ts, page[0].ID, 10)
	if len(older) != 1 || older[0].ID != "A" || !older[0].Deleted {
		t.Fatalf("older page = %+v", older)
	}
	reactions, _ := s.reactions(chat, []string{"A"})
	if len(reactions["A"]) != 1 || reactions["A"][0].Emoji != "👍" {
		t.Fatalf("reactions = %+v", reactions)
	}

	s.updateChat(chat, "archived", 1)
	if active, archivedCount, _ := s.chats(false); len(active) != 0 || archivedCount != 1 {
		t.Fatalf("active=%v archived=%d", active, archivedCount)
	}
}

func TestStorePinnedMessageRoundTrip(t *testing.T) {
	s, err := openStore(filepath.Join(t.TempDir(), "pins.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	chat := "441234@s.whatsapp.net"
	if err := s.putMessage(row{Chat: chat, ID: "P1", Ts: 1, Kind: "text", Text: "remember"}); err != nil {
		t.Fatal(err)
	}
	if err := s.setMessagePinned(chat, "P1", true, 2, 0); err != nil {
		t.Fatal(err)
	}
	rows, err := s.pinnedMessages(chat)
	if err != nil || len(rows) != 1 || !rows[0].Pinned || rows[0].Text != "remember" {
		t.Fatalf("pins=%+v err=%v", rows, err)
	}
	if err := s.setMessagePinned(chat, "P1", false, 3, 0); err != nil {
		t.Fatal(err)
	}
	rows, _ = s.pinnedMessages(chat)
	if len(rows) != 0 {
		t.Fatalf("unpin left rows: %+v", rows)
	}
	// A pin can precede its message; a fourth pin replaces the oldest; expired pins vanish.
	for i, id := range []string{"A", "B", "C", "D"} {
		if err := s.setMessagePinned(chat, id, true, int64(10+i), 0); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.setMessagePinned(chat, "E", true, 20, 21); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"A", "B", "C", "D", "E"} {
		if err := s.putMessage(row{Chat: chat, ID: id, Ts: 5, Kind: "text", Text: id}); err != nil {
			t.Fatal(err)
		}
	}
	rows, _ = s.pinnedMessages(chat)
	if len(rows) != 2 || rows[0].Text != "D" || rows[1].Text != "C" {
		t.Fatalf("want live pins D, C; got %+v", rows)
	}
}

func TestPollVotesTally(t *testing.T) {
	s, err := openStore(filepath.Join(t.TempDir(), "polls.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	chat := "123@g.us"
	spec := pollSpec{Question: "Lunch?", Options: []string{"Soup", "Salad", "Pie"}}
	hashes := whatsmeow.HashPollOptions([]string{"Pie", "Soup"})
	if got := optionIndexes(spec, hashes); len(got) != 2 || got[0] != 2 || got[1] != 0 {
		t.Fatalf("indexes=%v", got)
	}
	voter := "441@s.whatsapp.net"
	if err := s.setVote(chat, "P", voter, []int{1}, 10); err != nil {
		t.Fatal(err)
	}
	// An older vote arriving late must not replace a newer one.
	if err := s.setVote(chat, "P", voter, []int{0}, 5); err != nil {
		t.Fatal(err)
	}
	votes, _ := s.votes(chat, "P")
	if len(votes) != 1 || votes[0].Options[0] != 1 {
		t.Fatalf("votes=%+v", votes)
	}
	if err := s.setVote(chat, "P", voter, nil, 20); err != nil {
		t.Fatal(err)
	}
	if votes, _ = s.votes(chat, "P"); len(votes) != 0 {
		t.Fatalf("retracted vote left %+v", votes)
	}
}

func TestRenderText(t *testing.T) {
	name := func(jid string) string { return map[string]string{"447700900123@s.whatsapp.net": "Ann Lee"}[jid] }
	text, spans := renderText("hi @447700900123, *see* _this_ ~not~ ```a*b*c``` 😀 *x*", []string{"447700900123@s.whatsapp.net"}, name)
	if text != "hi @Ann Lee, see this not a*b*c 😀 x" {
		t.Fatalf("rendered %q", text)
	}
	want := []span{
		{Start: 3, Length: 8, Style: "mention", ID: "447700900123@s.whatsapp.net"},
		{Start: 13, Length: 3, Style: "bold"},
		{Start: 17, Length: 4, Style: "italic"},
		{Start: 22, Length: 3, Style: "strike"},
		{Start: 26, Length: 5, Style: "mono"},
		// After the emoji's two UTF-16 units.
		{Start: 35, Length: 1, Style: "bold"},
	}
	byStyle := map[string][]span{}
	for _, s := range spans {
		byStyle[s.Style+s.ID] = append(byStyle[s.Style+s.ID], s)
	}
	for _, w := range want {
		found := false
		for _, s := range byStyle[w.Style+w.ID] {
			found = found || s == w
		}
		if !found {
			t.Fatalf("missing %+v in %+v", w, spans)
		}
	}
	if len(spans) != len(want) {
		t.Fatalf("spans %+v", spans)
	}

	// Markers inside words, or around spaces, stay as typed.
	plain, none := renderText("2*3*4 and * not * and snake_case_name", nil, name)
	if plain != "2*3*4 and * not * and snake_case_name" || len(none) != 0 {
		t.Fatalf("plain text changed: %q %+v", plain, none)
	}

	body, jids := encodeMentions("hi @Ann Lee!", []span{{Start: 3, Length: 8, Style: "mention", ID: "447700900123@s.whatsapp.net"}})
	if body != "hi @447700900123!" || len(jids) != 1 || jids[0] != "447700900123@s.whatsapp.net" {
		t.Fatalf("encoded %q %v", body, jids)
	}
}

func TestDisappearingMessages(t *testing.T) {
	s, err := openStore(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	chat := "441234@s.whatsapp.net"
	if changed, _ := s.setChatTimer(chat, 86400); !changed || s.chatTimer(chat) != 86400 {
		t.Fatal("timer not set")
	}
	if changed, _ := s.setChatTimer(chat, 86400); changed {
		t.Fatal("same timer reported as a change")
	}
	s.putMessage(row{Chat: chat, ID: "A", Ts: 1000, Kind: "text", Text: "gone", ExpiresAt: 5000})
	s.putMessage(row{Chat: chat, ID: "B", Ts: 1000, Kind: "text", Text: "kept"})
	s.setReaction(chat, "A", "x", "👍")
	if next := s.nextExpiry(); next != 5000 {
		t.Fatalf("next expiry %d", next)
	}
	if chats, _ := s.purgeExpired(4999); len(chats) != 0 {
		t.Fatal("purged early")
	}
	if chats, _ := s.purgeExpired(5000); len(chats) != 1 || chats[0] != chat {
		t.Fatalf("purged %v", chats)
	}
	if m, _ := s.message(chat, "A"); m != nil {
		t.Fatal("expired message kept")
	}
	if m, _ := s.message(chat, "B"); m == nil {
		t.Fatal("lasting message removed")
	}
	if err := s.setBlocked([]string{chat}, true, true); err != nil {
		t.Fatal(err)
	}
	if row, _ := s.chat(chat); !row.Blocked || row.ExpireTimer != 86400 {
		t.Fatalf("chat %+v", row)
	}
}

func TestAlbumParts(t *testing.T) {
	child := &waE2E.Message{AssociatedChildMessage: &waE2E.FutureProofMessage{Message: &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{Mimetype: proto.String("image/jpeg")},
		MessageContextInfo: &waE2E.MessageContextInfo{MessageAssociation: &waE2E.MessageAssociation{
			AssociationType:  waE2E.MessageAssociation_MEDIA_ALBUM.Enum(),
			ParentMessageKey: &waCommon.MessageKey{ID: proto.String("PARENT")},
		}},
	}}}
	if got := parseMessage(child); got.Album != "PARENT" || got.Kind != "image" {
		t.Fatalf("album part parsed as %+v", got)
	}
	if got := parseMessage(&waE2E.Message{AlbumMessage: &waE2E.AlbumMessage{}}); !got.Ignore {
		t.Fatalf("album parent should be ignored, got %+v", got)
	}
}
