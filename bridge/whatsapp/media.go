// Media: downloading received attachments and sending files.

package wabridge

import (
	"errors"
	"mime"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9._-]`)

// mediaLeaf unwraps the envelopes a media message can arrive in.
func mediaLeaf(m *waE2E.Message) *waE2E.Message {
	for m != nil {
		switch {
		case m.GetDeviceSentMessage().GetMessage() != nil:
			m = m.GetDeviceSentMessage().GetMessage()
		case m.GetEphemeralMessage().GetMessage() != nil:
			m = m.GetEphemeralMessage().GetMessage()
		case m.GetViewOnceMessage().GetMessage() != nil:
			m = m.GetViewOnceMessage().GetMessage()
		case m.GetViewOnceMessageV2().GetMessage() != nil:
			m = m.GetViewOnceMessageV2().GetMessage()
		case m.GetDocumentWithCaptionMessage().GetMessage() != nil:
			m = m.GetDocumentWithCaptionMessage().GetMessage()
		default:
			return m
		}
	}
	return nil
}

func extensionFor(mimeType string) string {
	if exts, _ := mime.ExtensionsByType(strings.Split(mimeType, ";")[0]); len(exts) > 0 {
		return exts[0]
	}
	switch {
	case strings.HasPrefix(mimeType, "audio/ogg"):
		return ".ogg"
	case strings.HasPrefix(mimeType, "image/webp"):
		return ".webp"
	}
	return ".bin"
}

// DownloadMedia decrypts a received attachment into the media folder (once)
// and returns its path.
func (c *Client) DownloadMedia(chat, id string) (string, error) {
	stored, err := c.store.message(chat, id)
	if err != nil || stored == nil {
		return "", errors.New("message not found")
	}
	dir := filepath.Join(c.dir, "media")
	path := c.mediaPath(id, stored.Mime)
	if _, err := os.Stat(path); err == nil {
		return path, nil
	}
	raw, err := c.store.raw(chat, id)
	if err != nil || len(raw) == 0 {
		return "", errors.New("this attachment can't be downloaded")
	}
	var msg waE2E.Message
	if err := proto.Unmarshal(raw, &msg); err != nil {
		return "", err
	}
	ctx, cancel := timeout()
	defer cancel()
	data, err := c.wa.DownloadAny(ctx, mediaLeaf(&msg))
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return path, os.WriteFile(path, data, 0o600)
}

// SendMedia sends a file as a photo, video, audio (a voice note when
// voiceNote is set) or document, chosen by MIME type. It shows in the chat
// at once and is uploaded as part of the one attempt to send it.
func (c *Client) SendMedia(chat, path, mimeType, caption string, voiceNote bool) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	kind := mediaKind(mimeType)
	if kind == "audio" && voiceNote {
		mimeType = voiceNoteMime
	}
	r := row{ID: string(c.wa.GenerateMessageID()), Kind: kind, Text: caption, Filename: filepath.Base(path), Mime: mimeType, Size: int64(len(data))}
	// Our copy opens without downloading it back, and a failed upload is retried from it.
	if err := os.MkdirAll(filepath.Join(c.dir, "media"), 0o700); err != nil {
		return "", err
	}
	if err := os.WriteFile(c.mediaPath(r.ID, mimeType), data, 0o600); err != nil {
		return "", err
	}
	return c.queue(jid, r, func() (*waE2E.Message, error) { return c.uploadMedia(jid, r, data) })
}

// voiceNoteMime marks a voice note (rather than an audio file) among our messages.
const voiceNoteMime = "audio/ogg; codecs=opus"

func mediaKind(mimeType string) string {
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		return "image"
	case strings.HasPrefix(mimeType, "video/"):
		return "video"
	case strings.HasPrefix(mimeType, "audio/"):
		return "audio"
	}
	return "document"
}

// uploadMedia uploads a file and builds the message that sends it.
func (c *Client) uploadMedia(jid types.JID, r row, data []byte) (*waE2E.Message, error) {
	mediaType := map[string]whatsmeow.MediaType{
		"image": whatsmeow.MediaImage, "video": whatsmeow.MediaVideo, "audio": whatsmeow.MediaAudio,
	}[r.Kind]
	if mediaType == "" {
		mediaType = whatsmeow.MediaDocument
	}
	ctx, cancel := timeout()
	defer cancel()
	up, err := c.wa.Upload(ctx, data, mediaType)
	if err != nil {
		return nil, err
	}
	size := proto.Uint64(up.FileLength)
	msg := &waE2E.Message{}
	switch r.Kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{Caption: proto.String(r.Text), Mimetype: proto.String(r.Mime), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	case "video":
		msg.VideoMessage = &waE2E.VideoMessage{Caption: proto.String(r.Text), Mimetype: proto.String(r.Mime), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	case "audio":
		msg.AudioMessage = &waE2E.AudioMessage{Mimetype: proto.String(r.Mime), PTT: proto.Bool(r.Mime == voiceNoteMime), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	default:
		name := r.Filename
		msg.DocumentMessage = &waE2E.DocumentMessage{Caption: proto.String(r.Text), Mimetype: proto.String(r.Mime), FileName: &name, Title: &name,
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	}
	c.stampExpiry(jid, msg)
	return msg, nil
}
