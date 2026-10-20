package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroDetail;
import sntiago05.rejuv.domain.HeroInfo;

class HeroInfoServiceTest {

    private HeroResolver resolver;
    private DeadlockApiClient client;
    private HeroInfoService service;

    @BeforeEach
    void setUp() {
        resolver = mock(HeroResolver.class);
        client = mock(DeadlockApiClient.class);
        service = new HeroInfoService(resolver, client);
    }

    private static HeroDetail detail(String lore, boolean selectable, boolean disabled, boolean inDevelopment,
                                     String heroType, Map<String, HeroDetail.StatValue> stats, List<String> tags) {
        return new HeroDetail(7, "Infernus",
                new HeroDetail.Description(lore, "  Lights up enemies  ", "Burns foes"),
                heroType, 1, "Rapid Fire", tags, selectable, disabled, inDevelopment,
                new HeroDetail.Colors("#C93C26"), new HeroDetail.Images("https://assets-bucket.deadlock-api.com/c.png"),
                stats);
    }

    private static Map<String, HeroDetail.StatValue> stats(Object... keyValue) {
        Map<String, HeroDetail.StatValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put((String) keyValue[i], new HeroDetail.StatValue((Double) keyValue[i + 1]));
        }
        return map;
    }

    private void heroIs(String typed, String canonical, HeroDetail detail) {
        when(resolver.resolve(typed)).thenReturn(Optional.of(new HeroAsset(7, "hero_x", canonical)));
        when(client.getHeroDetail(canonical)).thenReturn(Optional.ofNullable(detail));
    }

    @Test
    void usesTheCanonicalNameFromTheResolverNotTheRawInput() {
        heroIs("  infe ", "Infernus", detail("Lore.", true, false, false, "marksman", Map.of(), List.of()));

        HeroInfo info = service.getInfo("  infe ");

        assertThat(info.name()).isEqualTo("Infernus");
        verify(client).getHeroDetail("Infernus");
        verify(client, never()).getHeroDetail("  infe ");
    }

    @Test
    void mapsFieldsAndTrimsText() {
        heroIs("Infernus", "Infernus", detail("Lore.", true, false, false, "marksman",
                stats("max_health", 830.0), List.of("Arsonist", " ", "Explosive")));

        HeroInfo info = service.getInfo("Infernus");

        assertThat(info.id()).isEqualTo(7);
        assertThat(info.role()).isEqualTo("Lights up enemies");
        assertThat(info.playstyle()).isEqualTo("Burns foes");
        assertThat(info.lore()).isEqualTo("Lore.");
        assertThat(info.heroType()).isEqualTo("marksman");
        assertThat(info.complexity()).isEqualTo(1);
        assertThat(info.gunTag()).isEqualTo("Rapid Fire");
        assertThat(info.tags()).containsExactly("Arsonist", "Explosive");
        assertThat(info.colorHex()).isEqualTo("#C93C26");
        assertThat(info.iconUrl()).endsWith("c.png");
        assertThat(info.playable()).isTrue();
    }

    @Test
    void unknownInputAndApiNotFoundBothThrowHeroNotFound() {
        when(resolver.resolve("zzz")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getInfo("zzz")).isInstanceOf(HeroNotFoundException.class);
        verify(client, never()).getHeroDetail("zzz");

        heroIs("Ghost", "Ghost", null);
        assertThatThrownBy(() -> service.getInfo("Ghost")).isInstanceOf(HeroNotFoundException.class);
    }

    @Test
    void apiFailuresPropagate() {
        when(resolver.resolve("Haze")).thenReturn(Optional.of(new HeroAsset(2, "hero_haze", "Haze")));
        when(client.getHeroDetail("Haze")).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> service.getInfo("Haze")).isInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void playableNeedsSelectableAndNotDisabledAndNotInDevelopment() {
        heroIs("A", "A", detail("x", false, false, false, null, Map.of(), List.of()));
        assertThat(service.getInfo("A").playable()).isFalse();
        heroIs("B", "B", detail("x", true, true, false, null, Map.of(), List.of()));
        assertThat(service.getInfo("B").playable()).isFalse();
        heroIs("C", "C", detail("x", true, false, true, null, Map.of(), List.of()));
        assertThat(service.getInfo("C").playable()).isFalse();
        heroIs("D", "D", detail("x", true, false, false, null, Map.of(), List.of()));
        assertThat(service.getInfo("D").playable()).isTrue();
        assertThat(service.getInfo("D").heroType()).isNull();
    }

    @Test
    void statsFollowTheFixedOrderAndSkipMissingAndNullValues() {
        Map<String, HeroDetail.StatValue> map = new LinkedHashMap<>();
        map.put("reload_speed", new HeroDetail.StatValue(1.0));
        map.put("crouch_speed", new HeroDetail.StatValue(4.75));
        map.put("max_health", new HeroDetail.StatValue(830.0));
        map.put("stamina", new HeroDetail.StatValue(null));
        map.put("max_move_speed", new HeroDetail.StatValue(6.7));
        heroIs("Infernus", "Infernus", detail("x", true, false, false, "marksman", map, List.of()));

        HeroInfo info = service.getInfo("Infernus");

        assertThat(info.stats()).extracting(HeroInfo.Stat::key)
                .containsExactly("max_health", "max_move_speed", "reload_speed");
        assertThat(info.stats().get(1).value()).isEqualTo(6.7);
    }

    @Test
    void missingDescriptionAndNullMapsAreHandled() {
        HeroDetail bare = new HeroDetail(7, "Infernus", null, null, null, null, null, true, false, false, null, null, null);
        heroIs("Infernus", "Infernus", bare);

        HeroInfo info = service.getInfo("Infernus");

        assertThat(info.role()).isNull();
        assertThat(info.playstyle()).isNull();
        assertThat(info.lore()).isNull();
        assertThat(info.tags()).isEmpty();
        assertThat(info.stats()).isEmpty();
        assertThat(info.colorHex()).isNull();
        assertThat(info.iconUrl()).isNull();
    }

    @Test
    void loreIsCutAtAWordBoundaryWithAnEllipsisOnlyWhenCut() {
        assertThat(HeroInfoService.cutAtWord("short  text\nhere", 600)).isEqualTo("short text here");
        assertThat(HeroInfoService.cutAtWord(null, 600)).isNull();

        String text = "word ".repeat(200).trim();
        String cut = HeroInfoService.cutAtWord(text, 600);
        assertThat(cut.length()).isLessThanOrEqualTo(600);
        assertThat(cut).endsWith("word...");
        assertThat(cut).doesNotContain("wor...");

        String exact = "a".repeat(600);
        assertThat(HeroInfoService.cutAtWord(exact, 600)).isEqualTo(exact);

        String noSpaces = "a".repeat(700);
        assertThat(HeroInfoService.cutAtWord(noSpaces, 600)).hasSize(600).endsWith("...");
    }
}
