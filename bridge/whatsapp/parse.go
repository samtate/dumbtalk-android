package wabridge

// Message extraction follows wacli's internal/wa/messages.go
// (github.com/openclaw/wacli, MIT License, Copyright (c) 2026 Peter
// Steinberger), trimmed to what DumbTalk displays.

import (
	"encoding/json"
	"strings"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

type parsed struct {
	Kind, Text, Filename, Mime      string
	Size                            int64
	QuoteID, QuoteSender, QuoteText string
	Forwarded, Edited               bool
	Poll                            string // JSON {question, options, multiple}
	Mentions                        []string
	Expiration                      uint32 // the disappearing timer it was sent with, seconds
	Album                           string // the album's parent message ID, for a photo or video sent in one

	// A chat's disappearing-message timer was set (to TimerSeconds).
	TimerSet     bool
	TimerSeconds uint32

	// Mutations of an earlier message instead of new content.
	ReactionTo, ReactionEmoji string
	IsReaction                bool
	RevokeID                  string
	EditID                    string
	Ignore                    bool
}

func parseMessage(m *waE2E.Message) parsed {
	var p parsed
	extract(m, &p)
	if p.Kind == "" && !p.IsReaction && p.RevokeID == "" && !p.Ignore && !p.TimerSet {
		p.Kind = "unsupported"
		p.Text = "Unsupported message — open WhatsApp to view"
	}
	return p
}

func extract(m *waE2E.Message, p *parsed) {
	if m == nil {
		return
	}
	if association := m.GetMessageContextInfo().GetMessageAssociation(); p.Album == "" &&
		association.GetAssociationType() == waE2E.MessageAssociation_MEDIA_ALBUM {
		p.Album = association.GetParentMessageKey().GetID()
	}
	// An album's parent only announces the photos and videos that follow, each its own message.
	if m.GetAlbumMessage() != nil {
		p.Ignore = true
		return
	}
	if sent := m.GetDeviceSentMessage(); sent.GetMessage() != nil {
		extract(sent.GetMessage(), p)
		return
	}
	if edited := m.GetEditedMessage().GetMessage(); edited != nil {
		p.Edited = true
		extract(edited, p)
		return
	}
	if protocol := m.GetProtocolMessage(); protocol != nil {
		switch protocol.GetType() {
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			p.EditID = protocol.GetKey().GetID()
			extract(protocol.GetEditedMessage(), p)
		case waE2E.ProtocolMessage_REVOKE:
			p.RevokeID = protocol.GetKey().GetID()
		case waE2E.ProtocolMessage_EPHEMERAL_SETTING:
			p.TimerSet, p.TimerSeconds = true, protocol.GetEphemeralExpiration()
		default:
			// History sync notices, key shares, ephemeral settings, …
			p.Ignore = true
		}
		return
	}
	for _, wrapper := range []*waE2E.FutureProofMessage{
		m.GetEphemeralMessage(), m.GetViewOnceMessage(), m.GetViewOnceMessageV2(),
		m.GetViewOnceMessageV2Extension(), m.GetDocumentWithCaptionMessage(), m.GetAssociatedChildMessage(),
	} {
		if inner := wrapper.GetMessage(); inner != nil {
			extract(inner, p)
			return
		}
	}
	if reaction := m.GetReactionMessage(); reaction != nil {
		p.IsReaction = true
		p.ReactionTo = reaction.GetKey().GetID()
		p.ReactionEmoji = reaction.GetText()
		return
	}
	if m.GetEncReactionMessage() != nil || m.GetPollUpdateMessage() != nil || m.GetSenderKeyDistributionMessage() != nil && onlyKeyDistribution(m) {
		p.Ignore = true
		return
	}

	switch {
	case m.GetConversation() != "":
		p.Kind, p.Text = "text", m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		p.Kind, p.Text = "text", m.GetExtendedTextMessage().GetText()
	case m.GetImageMessage() != nil:
		image := m.GetImageMessage()
		p.Kind, p.Text, p.Mime, p.Size = "image", image.GetCaption(), image.GetMimetype(), int64(image.GetFileLength())
	case m.GetVideoMessage() != nil:
		video := m.GetVideoMessage()
		p.Kind, p.Text, p.Mime, p.Size = "video", video.GetCaption(), video.GetMimetype(), int64(video.GetFileLength())
	case m.GetAudioMessage() != nil:
		audio := m.GetAudioMessage()
		p.Kind, p.Mime, p.Size = "audio", audio.GetMimetype(), int64(audio.GetFileLength())
	case m.GetDocumentMessage() != nil:
		document := m.GetDocumentMessage()
		p.Kind, p.Text, p.Filename = "document", document.GetCaption(), document.GetFileName()
		p.Mime, p.Size = document.GetMimetype(), int64(document.GetFileLength())
	case m.GetStickerMessage() != nil:
		p.Kind, p.Mime = "sticker", m.GetStickerMessage().GetMimetype()
	case m.GetContactMessage() != nil:
		p.Kind, p.Text = "text", "Contact: "+m.GetContactMessage().GetDisplayName()
	case m.GetContactsArrayMessage() != nil:
		p.Kind, p.Text = "text", "Contacts: "+m.GetContactsArrayMessage().GetDisplayName()
	case m.GetLocationMessage() != nil:
		p.Kind, p.Text = "text", "📍 "+firstNonEmpty(m.GetLocationMessage().GetName(), "Location")
	case m.GetLiveLocationMessage() != nil:
		p.Kind, p.Text = "text", "📍 Live location"
	case m.GetGroupInviteMessage() != nil:
		p.Kind, p.Text = "text", "Group invite: "+m.GetGroupInviteMessage().GetGroupName()
	case m.GetCall() != nil || m.GetCallLogMesssage() != nil:
		p.Kind, p.Text = "system", "📞 Call"
	default:
		if poll := pollCreation(m); poll != nil {
			options := make([]string, 0, len(poll.GetOptions()))
			for _, option := range poll.GetOptions() {
				options = append(options, option.GetOptionName())
			}
			encoded, _ := json.Marshal(map[string]any{
				"question": poll.GetName(),
				"options":  options,
				"multiple": poll.GetSelectableOptionsCount() != 1,
			})
			p.Kind, p.Poll = "poll", string(encoded)
		}
	}

	if ctx := contextInfo(m); ctx != nil {
		p.QuoteID = ctx.GetStanzaID()
		p.QuoteSender = ctx.GetParticipant()
		if quoted := ctx.GetQuotedMessage(); quoted != nil {
			p.QuoteText = previewText(quoted)
		}
		p.Forwarded = ctx.GetIsForwarded() || ctx.GetForwardingScore() > 0
		p.Mentions = ctx.GetMentionedJID()
		p.Expiration = ctx.GetExpiration()
	}
}

// Group messages often carry nothing but a sender key for later messages.
func onlyKeyDistribution(m *waE2E.Message) bool {
	clone := proto.Clone(m).(*waE2E.Message)
	clone.SenderKeyDistributionMessage = nil
	clone.MessageContextInfo = nil
	return previewText(clone) == "" && pollCreation(clone) == nil
}

func pollCreation(m *waE2E.Message) *waE2E.PollCreationMessage {
	for _, poll := range []*waE2E.PollCreationMessage{
		m.GetPollCreationMessage(), m.GetPollCreationMessageV2(), m.GetPollCreationMessageV3(),
		m.GetPollCreationMessageV5(), m.GetPollCreationMessageV6(),
	} {
		if poll != nil {
			return poll
		}
	}
	if inner := m.GetPollCreationMessageV4().GetMessage(); inner != nil {
		return pollCreation(inner)
	}
	return nil
}

func contextInfo(m *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case m.GetExtendedTextMessage() != nil:
		return m.GetExtendedTextMessage().GetContextInfo()
	case m.GetImageMessage() != nil:
		return m.GetImageMessage().GetContextInfo()
	case m.GetVideoMessage() != nil:
		return m.GetVideoMessage().GetContextInfo()
	case m.GetAudioMessage() != nil:
		return m.GetAudioMessage().GetContextInfo()
	case m.GetDocumentMessage() != nil:
		return m.GetDocumentMessage().GetContextInfo()
	case m.GetStickerMessage() != nil:
		return m.GetStickerMessage().GetContextInfo()
	}
	if poll := pollCreation(m); poll != nil {
		return poll.GetContextInfo()
	}
	return nil
}

// previewText is a one-line description used for quotes.
func previewText(m *waE2E.Message) string {
	var p parsed
	extract(m, &p)
	switch {
	case strings.TrimSpace(p.Text) != "":
		return p.Text
	case p.Kind == "image":
		return "Photo"
	case p.Kind == "video":
		return "Video"
	case p.Kind == "audio":
		return "Voice note"
	case p.Kind == "document":
		return firstNonEmpty(p.Filename, "Document")
	case p.Kind == "sticker":
		return "Sticker"
	case p.Kind == "poll":
		return "Poll"
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
