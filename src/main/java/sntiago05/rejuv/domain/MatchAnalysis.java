package sntiago05.rejuv.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "match_analysis")
public class MatchAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "match_id", nullable = false)
    private long matchId;

    @Column(name = "hero_id", nullable = false)
    private int heroId;

    @Column(name = "result_json", nullable = false)
    private String resultJson;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "input_json")
    private String inputJson;

    protected MatchAnalysis() {
        // required by JPA
    }

    public MatchAnalysis(long accountId, long matchId, int heroId, String resultJson,
                         String model, int inputTokens, int outputTokens, Instant createdAt) {
        this(accountId, matchId, heroId, resultJson, model, inputTokens, outputTokens, createdAt, null);
    }

    public MatchAnalysis(long accountId, long matchId, int heroId, String resultJson,
                         String model, int inputTokens, int outputTokens, Instant createdAt,
                         String inputJson) {
        this.accountId = accountId;
        this.matchId = matchId;
        this.heroId = heroId;
        this.resultJson = resultJson;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = createdAt;
        this.publicId = UUID.randomUUID();
        this.inputJson = inputJson;
    }

    public Long getId() {
        return id;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getMatchId() {
        return matchId;
    }

    public int getHeroId() {
        return heroId;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getInputJson() {
        return inputJson;
    }
}
