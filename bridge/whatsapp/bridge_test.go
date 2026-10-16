package wabridge

import (
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
	s.editMessage(chat, "C", "C2")
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
