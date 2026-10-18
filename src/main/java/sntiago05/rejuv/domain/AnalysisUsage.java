package sntiago05.rejuv.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "analysis_usage")
public class AnalysisUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_user_id", nullable = false)
    private long discordUserId;

    @Column(name = "match_id", nullable = false)
    private long matchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisUsage() {
        // required by JPA
    }

    public AnalysisUsage(long discordUserId, long matchId, Instant createdAt) {
        this.discordUserId = discordUserId;
        this.matchId = matchId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getDiscordUserId() {
        return discordUserId;
    }

    public long getMatchId() {
        return matchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
