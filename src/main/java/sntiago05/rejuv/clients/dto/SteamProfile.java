package sntiago05.rejuv.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SteamProfile(@JsonProperty("account_id") long accountId,
                           @JsonProperty("personaname") String personaName) { }
