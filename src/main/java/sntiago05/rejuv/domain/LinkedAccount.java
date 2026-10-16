package sntiago05.rejuv.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "linked_account")
public class LinkedAccount {

    @Id
    @Column(name = "discord_user_id")
    private long discordUserId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected LinkedAccount() {
        // required by JPA
    }

    public LinkedAccount(long discordUserId, long accountId, Instant linkedAt) {
        this.discordUserId = discordUserId;
        this.accountId = accountId;
        this.linkedAt = linkedAt;
    }

    public long getDiscordUserId() {
        return discordUserId;
    }

    public long getAccountId() {
        return accountId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
