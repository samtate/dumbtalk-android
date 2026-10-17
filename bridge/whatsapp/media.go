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
	path := filepath.Join(dir, unsafeName.ReplaceAllString(id, "_")+extensionFor(stored.Mime))
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

// SendMedia uploads a file and sends it as a photo, video, audio (a voice
// note when voiceNote is set) or document, chosen by MIME type.
func (c *Client) SendMedia(chat, path, mimeType, caption string, voiceNote bool) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	kind, mediaType := "document", whatsmeow.MediaDocument
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		kind, mediaType = "image", whatsmeow.MediaImage
	case strings.HasPrefix(mimeType, "video/"):
		kind, mediaType = "video", whatsmeow.MediaVideo
	case strings.HasPrefix(mimeType, "audio/"):
		kind, mediaType = "audio", whatsmeow.MediaAudio
	}
	ctx, cancel := timeout()
	defer cancel()
	up, err := c.wa.Upload(ctx, data, mediaType)
	if err != nil {
		return "", err
	}
	size := proto.Uint64(up.FileLength)
	msg := &waE2E.Message{}
	switch kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{Caption: proto.String(caption), Mimetype: proto.String(mimeType), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	case "video":
		msg.VideoMessage = &waE2E.VideoMessage{Caption: proto.String(caption), Mimetype: proto.String(mimeType), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	case "audio":
		if voiceNote {
			mimeType = "audio/ogg; codecs=opus"
		}
		msg.AudioMessage = &waE2E.AudioMessage{Mimetype: proto.String(mimeType), PTT: proto.Bool(voiceNote), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	default:
		name := filepath.Base(path)
		msg.DocumentMessage = &waE2E.DocumentMessage{Caption: proto.String(caption), Mimetype: proto.String(mimeType), FileName: &name, Title: &name,
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size}
	}
	expires := c.stampExpiry(jid, msg)
	resp, err := c.wa.SendMessage(ctx, jid, msg)
	if err != nil {
		return "", err
	}
	raw, _ := proto.Marshal(msg)
	sent := row{
		Chat: jid.String(), ID: resp.ID, Sender: c.ownJID().String(), FromMe: true, Ts: resp.Timestamp.UnixMilli(),
		Kind: kind, Text: caption, Filename: filepath.Base(path), Mime: mimeType, Size: int64(up.FileLength), Status: "sent", Raw: raw,
		ExpiresAt: expiresAt(resp.Timestamp.UnixMilli(), expires),
	}
	if err := c.store.putMessage(sent); err != nil {
		return "", err
	}
	// Keep our copy as the downloaded file so it opens without a round trip.
	dir := filepath.Join(c.dir, "media")
	if os.MkdirAll(dir, 0o700) == nil {
		_ = os.WriteFile(filepath.Join(dir, unsafeName.ReplaceAllString(resp.ID, "_")+extensionFor(mimeType)), data, 0o600)
	}
	emit(c.listener, "chats", nil)
	return marshal(c.toJSONMessage(sent, nil))
}
