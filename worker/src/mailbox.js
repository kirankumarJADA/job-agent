export class VerificationError extends Error {}

export class MockMailboxClient {
  constructor(baseUrl, fetchImpl = fetch) {
    this.baseUrl = baseUrl;
    this.fetch = fetchImpl;
  }

  async getVerification({ applicationId, senderDomain, receivedAfter }) {
    const response = await this.fetch(`${this.baseUrl}/mailbox/messages?applicationId=${encodeURIComponent(applicationId)}`);
    if (!response.ok) throw new VerificationError(`MAILBOX_UNAVAILABLE:${response.status}`);
    const messages = await response.json();
    const candidate = messages.find((message) => {
      const sender = String(message.sender ?? '').toLowerCase();
      const received = new Date(message.receivedAt).getTime();
      return sender.endsWith(`@${senderDomain.toLowerCase()}`)
        && message.applicationId === applicationId
        && received >= receivedAfter;
    });
    if (!candidate) throw new VerificationError('VERIFICATION_NOT_FOUND_OR_MISMATCHED');
    const otpMatch = String(candidate.body ?? '').match(/\b(\d{6})\b/);
    const linkMatch = String(candidate.body ?? '').match(/https?:\/\/[^\s"']+/);
    if (!otpMatch && !linkMatch) throw new VerificationError('VERIFICATION_DATA_NOT_FOUND');
    return {
      messageId: candidate.id,
      applicationId: candidate.applicationId,
      senderDomain,
      otp: otpMatch?.[1] ?? null,
      link: linkMatch?.[0] ?? null,
      receivedAt: candidate.receivedAt,
    };
  }
}
