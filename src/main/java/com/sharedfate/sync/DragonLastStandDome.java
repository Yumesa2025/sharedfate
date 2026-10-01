package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.phys.Vec3;

/**
 * 「최후의 저항」의 <b>반구 블록 파괴</b>. 드래곤 주변 반경 25칸 반구 안에서 <b>포디움보다 높은
 * 블록</b>을 계속 부순다.
 *
 * <h2>패턴이 아니다 — 상시 규칙이다</h2>
 *
 * <p>{@link DragonLastStand.Pattern} 으로 뽑히지 않는다. {@link DragonLastStandZone}(안전지대)와
 * {@code DragonLastStandPatterns.tickLightning}(상시 번개)처럼 <b>진입부터 끝까지 저 혼자 도는
 * 시계</b>이고, {@link DragonLastStand#tick} 이 매 틱 {@link #tick} 을 넘기기만 한다.
 *
 * <p>사람 말: <b>「드래곤 주변에 y가 높은 블럭들을 계속 파괴하는게 있으면좋겟고 반구형태로
 * 드래곤주변, 블럭으로 패턴피할수잇으니」</b> — 곧 이것이 막는 것은 <b>블록으로 패턴을 피하는
 * 것</b>이다. 부채꼴 브레스도 번개도 공허 흡입도 지붕 한 장으로 무력화되면 이 페이즈가 성립하지
 * 않는다.
 *
 * <h2>⚠⚠ 바닥은 <b>절대</b> 부수지 않는다 — 두 겹으로 막았다</h2>
 *
 * <p>엔드 중앙 섬에 구멍이 나면 <b>허공 낙사</b>이고, 공유 체력이라 한 사람이 떨어지면 팀 전멸
 * → 월드 삭제다. 사람이 <b>「바닥은 안 부수고」</b>라고 못박았다. 그래서 막는 것이 둘이고,
 * <b>한 겹만으로도 섬이 안전하다.</b>
 *
 * <ol>
 *   <li><b>훑기 바닥보다 낮은 칸은 아예 읽지 않는다.</b> {@link #sweepFloorY} 가 <b>섬 표면 위의
 *       첫 빈 칸</b>이고, 부수는 것은 <b>{@code y ≥ sweepFloorY}</b> 뿐이다. 섬 표면의 맨 위
 *       블록은 그보다 <b>정확히 한 칸 아래</b>({@code sweepFloorY − 1})에 있다 — 근거는
 *       {@link #sweepFloorY} 에 적어 두었다. 곧 <b>사람이 표면에 쌓은 것은 첫 칸부터 부수면서
 *       표면 자신은 한 번도 읽지 않는다</b>. 2026-10-01 에 바닥을 네 칸 내리면서 이 겹이
 *       <b>얇아졌다</b>(전에는 네 칸의 여유가 있었고 지금은 한 칸이다). 한 칸이면 충분한 것은
 *       그 한 칸이 <b>계산으로 나온 값</b>이고 사람이 쌓는 높이와 무관하기 때문이지만, 여기서
 *       <b>한 칸이라도 더 내리면 섬 표면이 범위에 들어온다</b> — 그때 섬을 지키는 것은 아래 ②
 *       하나뿐이 되고 {@link #sweepFloorY} 의 하이트맵 거름망도 함께 죽는다. <b>더 내리지
 *       말 것.</b></li>
 *   <li><b>{@code BlockTags.DRAGON_IMMUNE} 을 존중한다.</b> 26.3 의 그 태그를 풀어 읽었더니
 *       <b>{@code end_stone} 과 {@code bedrock} 이 들어 있다</b> — 섬을 이루는 블록이 그 둘이므로
 *       설령 위의 ①이 틀리더라도 섬은 한 칸도 안 없어진다. 같은 태그가 <b>흑요석 · 기반암 ·
 *       엔드 포털 · 엔드 게이트웨이 · 포털 틀 · 쇠창살</b>까지 들고 있어 기둥과 출구 포털도 함께
 *       지켜진다(아래 「확인한 사실」)</li>
 * </ol>
 *
 * <h2>확인한 사실 — 26.3 바이트코드와 태그 파일을 직접 읽었다</h2>
 *
 * <table border="1">
 *   <caption>지켜야 하는 것과 그것을 지키는 근거</caption>
 *   <tr><th>지켜야 하는 것</th><th>근거</th></tr>
 *   <tr><td><b>섬 바닥</b>({@code end_stone})</td>
 *       <td>훑기 바닥 아래다(맨 위 블록이 {@code sweepFloorY − 1} 이다) +
 *           {@code DRAGON_IMMUNE} 에 {@code minecraft:end_stone} 이 있다</td></tr>
 *   <tr><td><b>기반암 포디움 · 출구 포털</b></td>
 *       <td>⚠ <b>2026-10-01 부터 훑기 바닥 <u>위</u>다.</b> {@code EndPodiumFeature.place} 의
 *           바이트코드에서 확인한 자리는 기반암 기둥이
 *           {@code sweepFloorY … sweepFloorY + 3}, 포디움 테두리 기반암과 엔드 포털이
 *           {@code sweepFloorY} 다 — 곧 <b>넷 다 새 범위 안</b>이다. 그래서 여기서는
 *           <b>태그 한 겹이 전부</b>이고, {@code DRAGON_IMMUNE} 에
 *           {@code bedrock}·{@code end_portal}·{@code end_portal_frame}·{@code end_gateway} 가
 *           있어 {@link #breakable} 이 첫 물음에서 거짓을 돌려준다(태그 파일을 다시 읽어
 *           확인했다). <b>한 칸도 안 없어진다</b></td></tr>
 *   <tr><td><b>흑요석 기둥과 쇠창살 우리</b></td>
 *       <td>기둥이 중앙에서 <b>40칸 넘게</b> 떨어져 있어 반경 {@value #RADIUS} 에 <b>닿지
 *           않는다</b>. 그래도 {@code DRAGON_IMMUNE} 에 {@code obsidian}·{@code iron_bars} 가
 *           있어 반경을 올리는 사람이 실수해도 안전하다</td></tr>
 * </table>
 *
 * <p>⚠ <b>포디움에서 지켜지지 <u>않는</u> 것 하나를 적어 둔다 — 벽 횃불 넷.</b>
 * {@code EndPodiumFeature.place} 가 기둥 옆 네 방향
 * ({@code p.above(2)} 의 수평 이웃, 곧 {@code sweepFloorY + 2})에 {@code wall_torch} 를 세우는데
 * 그것은 {@code DRAGON_IMMUNE} 도 {@code DRAGON_TRANSPARENT} 도 아니다(26.3 태그 파일을 둘 다
 * 읽었다 — {@code dragon_transparent} 는 {@code light} 와 {@code #fire} 두 줄뿐이다). 곧 진입 뒤
 * 첫 훑기에 <b>포디움 횃불이 사라진다.</b> 연출뿐이고 낙사·포털과는 무관해 그대로 둔다 —
 * 지키고 싶으면 {@link #breakable} 에 우리 목록을 더하는 것이 아니라 포디움 XZ 를 제외해야 하고,
 * 그 예외는 「포디움 위에 쌓은 발판」까지 함께 살려 준다.
 *
 * <p>⚠ 반경을 {@value #RADIUS} 에서 올리려는 사람은 <b>기둥이 아니라 섬 밖</b>을 먼저 볼 것.
 * 태그가 기둥은 지켜 주지만 사람이 섬 밖에 놓은 발판은 지켜 주지 않는다 — 다만 그 발판은
 * 훑기 바닥보다 <b>낮을 때만</b> 낙사와 이어지고, 이 규칙은 훑기 바닥 위만 만진다.
 *
 * <h2>바닐라 드래곤과 <b>같은 방식으로</b> 부순다</h2>
 *
 * <p>26.3 {@code EnderDragon.checkWalls} 를 풀어 읽고 그 네 줄을 그대로 옮겼다. 새 규칙을 만들지
 * 않은 것이 요점이다 — 「드래곤이 몸으로 부수는 것」과 「반구가 부수는 것」이 같은 블록에 대해
 * 다르게 답하면 사람이 두 가지를 배운다.
 *
 * <ul>
 *   <li>{@code isAir()} 는 건너뛴다</li>
 *   <li>{@code BlockTags.DRAGON_TRANSPARENT} 는 건너뛴다(26.3 에서 그 태그의 내용은
 *       {@code #wither_immune} 과 같은 꼴의 한 줄이고, 바닐라 드래곤이 「지나가도 되는 것」으로
 *       치는 목록이다)</li>
 *   <li>{@code BlockTags.DRAGON_IMMUNE} 은 건너뛴다</li>
 *   <li>⚠ <b>{@code mobGriefing} 게임룰을 본다.</b> 아래 「게임룰」을 볼 것</li>
 *   <li><b>{@code removeBlock(pos, false)}</b> 로 지운다 — {@code false} 가 <b>드롭 없음</b>이고
 *       사람이 <b>「아니 파괴」</b>라고 정한 것이 그것이다</li>
 * </ul>
 *
 * <p>⚠ <b>{@code destroyBlock} 을 쓰지 않는다.</b> 이 저장소에는 그것을 쓰면 안 되는 이유가 이미
 * 적혀 있다 — {@code PerkBlockBreaks} 의 「{@code removeBlock} 을 쓴다」를 볼 것. Fabric 의
 * {@code PlayerBlockBreakEvents.AFTER} 가 {@code ServerPlayerGameMode.destroyBlock} 안에서
 * 발화하므로, 그 길로 지우면 <b>증강의 블록 파괴 효과(메아리 채굴 · 같은 종류 채굴)가 딸려
 * 돈다.</b> 반구가 부순 블록이 사람의 채굴로 읽히는 순간 경험치·드롭·연쇄가 전부 얹힌다.
 *
 * <h2>게임룰 — {@code mobGriefing} 을 존중한다</h2>
 *
 * <p>끄면 이 규칙도 함께 꺼진다. 그것이 <b>일부러 고른 것</b>이다.
 *
 * <ul>
 *   <li>바닐라 드래곤의 블록 부수기가 이미 그 규칙에 달려 있다. 우리만 무시하면 <b>「드래곤은
 *       안 부수는데 반구는 부순다」</b>가 되고, 그 어긋남은 로그 어디에도 안 남는다</li>
 *   <li>이 저장소가 이미 같은 태도다 — 「연쇄 포격」·「기둥 화염구」·「종말의 비」가
 *       <b>{@code mobGriefing} 에 묶여 있어 우리만 끌 수 없다</b>고 적어 두었다</li>
 * </ul>
 *
 * <p>⚠ 대가는 <b>그 규칙을 끈 서버에서 「블록으로 패턴을 피할 수 있다」가 되돌아온다</b>는 것이다.
 * 사람이 그것을 원하지 않으면 여기 한 줄을 지우면 되고, <b>지울 때 위 두 문단도 함께 고칠 것.</b>
 *
 * <h2>⚠ 성능 — 반구 안이 3만 칸인데 실제로는 <b>2천 번쯤</b> 본다</h2>
 *
 * <p>반경 25 반구는 {@code 2/3·π·25³ ≈ 32700} 칸이다. {@value #SWEEP_TICKS} 틱마다 그것을 전부
 * 읽으면 서버가 앓는다. 거름망이 <b>둘</b>이고 순서가 중요하다.
 *
 * <ol>
 *   <li><b>빈 구획을 건너뛴다</b> — {@code LevelChunkSection.hasOnlyAir()}.
 *       {@code TrialDryWorld.evaporate} 가 {@code hasFluid()} 로 120만 칸을 392번으로 줄인 것과
 *       <b>같은 수법</b>이다. 청크 <b>16개</b> × 구획 <b>2개</b> = <b>32번</b>의 비교로, 훑는
 *       범위가 통째로 공기인 청크는 256칸(=열)을 한 번에 건너뛴다</li>
 *   <li><b>하이트맵으로 열을 건너뛴다</b> — {@code chunk.getHeight(WORLD_SURFACE, x, z)}.
 *       ⚠ <b>이것이 여기서 진짜로 일하는 거름망이다.</b> 구획 하나는 16칸 두께라 훑기 바닥
 *       {@link #sweepFloorY} 를 담은 구획에는 <b>바닥 아래의 섬 돌</b>이 들어 있어
 *       {@code hasOnlyAir()} 가 거짓이고, 그래서 ①만으로는 바닐라 엔드에서 한 칸도 못 건너뛴다.
 *       열마다 하이트맵을 <b>한 번</b> 읽어 맨 위 블록이 훑기 바닥보다 낮으면 그 열을 통째로
 *       건너뛴다 — 아무것도 쌓지 않은 판에서는 <b>모든 열이 여기서 걸러진다</b></li>
 * </ol>
 *
 * <p>⚠ <b>2026-10-01 에 바닥을 네 칸 내렸는데도 ②가 여전히 모든 열을 거르는 까닭</b>을 적어 둔다.
 * 내린 바닥은 <b>섬 표면 위의 첫 빈 칸</b>({@code = 포디움 원점의 y}, 근거는
 * {@link #sweepFloorY})이고, 맨손 아레나에서 하이트맵이 돌려주는 「맨 위 블록의 y」는 섬 표면
 * 그 자신이라 <b>{@code sweepFloorY − 1}</b> 이다. 곧 {@code top < sweepFloor} 가
 * <b>{@code sweepFloorY − 1 < sweepFloorY}</b> 로 참이 되어 열이 걸러진다 — <b>한 칸 차이로
 * 성립한다.</b> 여유가 한 칸뿐이므로 <b>바닥을 한 칸이라도 더 내리면 거름망이 통째로 죽는다</b>
 * (모든 열이 통과해 1961열 × 최대 30칸을 매 초 읽는다). 값을 만지려는 사람은 이 문단과
 * 「바닥은 절대 부수지 않는다」의 ①을 함께 볼 것.
 *
 * <p>⚠ <b>{@code TrialDryWorld} 가 하이트맵을 못 쓴 까닭</b>도 적어 둔다. 그쪽이 찾는 것은
 * <b>유체</b>이고 유체에는 하이트맵이 없다({@code WORLD_SURFACE} 는 「공기가 아닌 블록」이라 남의
 * 블록 안에 잠긴 물을 찾지 못한다). 그래서 그쪽은 구획의 유체 개수에 기댈 수밖에 없었고, 여기는
 * 찾는 것이 <b>블록</b>이라 하이트맵이 정확하다 — 같은 문제의 다른 답이다.
 *
 * <p>{@code WORLD_SURFACE} 를 고른 근거. 26.3 {@code Heightmap.Types.WORLD_SURFACE} 는 판별식이
 * {@code NOT_AIR} 이고 {@code Usage.CLIENT} 라 <b>월드생성 뒤에도 살아 있으며</b>,
 * {@code LevelChunk.setBlockState} 가 매 설치·파괴마다 그것을 갱신한다(바이트코드에서
 * {@code MOTION_BLOCKING}·{@code MOTION_BLOCKING_NO_LEAVES}·{@code OCEAN_FLOOR}·
 * {@code WORLD_SURFACE} 네 개를 갱신하는 것을 확인했다). {@code MOTION_BLOCKING} 을 쓰면 안 된다 —
 * <b>지나갈 수 있는 블록(횃불·간판·양탄자)으로 만든 지붕을 놓친다.</b>
 *
 * <p>{@code chunk.getHeight(type, x, z)} 가 돌려주는 것은 <b>맨 위 블록 자신의 y</b> 다
 * ({@code getFirstAvailable(x, z) − 1}, 바이트코드로 확인했다). {@code TrialEnderPulse.Ground} 가
 * 거기에 1 을 더해 「설 수 있는 높이」로 쓰는 것과 <b>한 칸 다르다</b> — 여기는 「부술 블록」을
 * 찾으므로 더하지 않는다.
 *
 * <p>청크는 {@code getChunkNow} 로만 본다. 없으면 건너뛴다 — 블록을 부수자고 청크를 불러오면
 * 「숨는 것을 막으려다 지형을 새로 만드는」 일이 된다({@code TrialDryWorld} 와 같은 판단이다).
 *
 * <h2>⚠ 한 번에 부수는 수에 천장을 둔다</h2>
 *
 * <p>사람이 16×16 발판을 깔아 두면 한 번의 훑기에서 수천 칸이 걸린다. 블록 갱신 자체는 바닐라가
 * 구획 단위로 묶어 보내지만({@code ChunkHolder.blockChanged}) 먼지 연출은 묶이지 않으므로
 * {@link #MAX_BREAKS_PER_SWEEP} 으로 자른다. 잘린 나머지는 <b>다음 훑기가 이어서 먹는다</b> —
 * 사람 말이 「계속 파괴하는게」였으니 한 번에 다 없애야 할 이유가 없고, 지붕이 조금씩 갈려
 * 나가는 것이 오히려 보인다.
 */
public final class DragonLastStandDome {

	/**
	 * 반구의 반경(칸). <b>사람이 정한 값이다.</b>
	 *
	 * <p>사람이 <b>「기둥은 안 들어갈 정도로」</b>라고 했고, 흑요석 기둥은 중앙에서 40칸 넘게
	 * 떨어져 있어 한참 밖이다. {@code TrialRisks.ARENA_RADIUS}(40)보다 안쪽이므로 반구가 섬 밖
	 * 허공에 걸치는 일도 없다.
	 */
	static final double RADIUS = 25.0;

	/**
	 * 훑는 주기(틱). 1초. <b>사람이 정한 값이다.</b>
	 *
	 * <p>매 틱 돌 일이 아니다 — 까닭은 클래스 설명의 「성능」에 있다. 1초면 「블록으로 숨을 수
	 * 없다」가 눈에는 그대로 보인다({@code DragonLastStand.ENDERMAN_SWEEP_TICKS} 가 같은 값을 같은
	 * 근거로 들고 있다).
	 */
	static final int SWEEP_TICKS = 20;

	/**
	 * 한 번의 훑기에서 부수는 블록 수의 천장.
	 *
	 * <p><b>사람이 정하지 않았다.</b> 1초에 {@value #MAX_BREAKS_PER_SWEEP} 칸이면 5×5 지붕(25칸)이
	 * 한 번에 사라지고 16×16 발판(256칸)도 한 번에 사라진다 — 곧 <b>사람이 실제로 숨을 만한
	 * 구조물은 모두 한 번에 없어지고</b>, 천장에 걸리는 것은 판 전체를 덮는 수준의 건축뿐이다.
	 *
	 * <p>천장이 필요한 까닭은 먼지 연출이 블록 갱신과 달리 <b>묶여 나가지 않기</b> 때문이다
	 * ({@link #puff}). 연출을 열마다 하나로 줄여 두었으므로 꾸러미는 이 수를 넘지 않고, 그
	 * <b>{@value #MAX_BREAKS_PER_SWEEP} 장이 한 틱에 나가도 파티클 예산
	 * ({@code TrialLandingShock.MAX_POINTS_PER_TICK} = 440)보다 적다</b> — 곧 이 규칙이 가장
	 * 바쁜 틱을 갈아치우는 일은 없다. 블록 갱신 쪽은 바닐라가 구획 단위로 묶어 보내므로
	 * ({@code ChunkHolder.blockChanged}) 세지 않아도 된다.
	 */
	static final int MAX_BREAKS_PER_SWEEP = 256;

	/**
	 * ⚠ <b>훑기 바닥을 기하 원점에서 내리는 칸 수. 바닐라 상수를 그대로 참조한다.</b>
	 *
	 * <p>{@code net.minecraft.world.level.levelgen.feature.EndPodiumFeature.PODIUM_PILLAR_HEIGHT}
	 * 는 <b>{@code public static final int} = 4</b> 다(26.3 바이트코드에서 접근 수준과 값을 함께
	 * 확인했다 — 그래서 우리 숫자를 적지 않고 참조한다. 컴파일 상수라 {@code EndPodiumFeature}
	 * 가 런타임 의존으로 남지도 않는다).
	 *
	 * <p><b>왜 하필 이 값인가.</b> 기하 원점({@link #domeOriginY})이 포디움 원점 {@code p} 에서
	 * {@code p.y + 4} 이고, 그 <b>4</b> 가 바로 이 상수다 — {@code EndPodiumFeature.place} 가
	 * {@code p.above(0..3)} 에 기반암을 세우므로 하이트맵이 {@code p.y + 4} 를 돌려준다(셈은
	 * {@link #sweepFloorY} 에 있다). 곧 <b>원점에서 이 값을 빼면 포디움 원점 {@code p.y} 가
	 * 나오고, 그것이 섬 표면 위의 첫 빈 칸</b>이다. 같은 4 를 두 번 쓰는 것이 아니라 <b>한 번 쓴
	 * 4 를 되돌리는 것</b>이라 이 상수가 이 자리에 맞다.
	 *
	 * <p>⚠ 판이 올라 바닐라가 기둥 높이를 바꾸면 <b>훑기 바닥이 저절로 따라 움직인다.</b> 그것이
	 * 옳은 쪽이다 — 이 값은 「사람이 고른 두께」가 아니라 <b>포디움 기하에서 나오는 값</b>이다.
	 */
	static final int PODIUM_PILLAR_HEIGHT = EndPodiumFeature.PODIUM_PILLAR_HEIGHT;

	/**
	 * 마지막으로 훑은 시각.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #clearState()} 로 반드시 비운다 —
	 * {@code DragonLastStand.clearState} 가 부른다.
	 */
	private static long lastSweep = Long.MIN_VALUE;

	private DragonLastStandDome() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 붙박이 드래곤을 못박은 뒤에 넘긴다.
	 *
	 * <p>{@value #SWEEP_TICKS} 틱마다 한 번만 실제로 훑는다.
	 *
	 * @param center 드래곤을 못박아 둔 자리. 반구의 중심이고 그 {@code y} 에서 기하 원점
	 *               ({@link #domeOriginY})과 훑기 바닥({@link #sweepFloorY})이 함께 나온다
	 * @param now    받은 틱. {@code getGameTime} 을 여기서 다시 읽지 않는다
	 */
	static void tick(ServerLevel end, Vec3 center, long now) {
		if (lastSweep != Long.MIN_VALUE && now - lastSweep < SWEEP_TICKS) {
			return;
		}
		lastSweep = now;
		sweep(end, center);
	}

	/**
	 * 한 번 훑는다.
	 *
	 * @return 실제로 부순 블록 수. 시험과 로그가 들여다보는 값이다
	 */
	static int sweep(ServerLevel end, Vec3 center) {
		if (!end.getGameRules().get(GameRules.MOB_GRIEFING)) {
			// 게임룰을 존중한다. 까닭은 클래스 설명의 「게임룰」에 있다.
			return 0;
		}
		// ⚠ 두 높이를 갈라 쓴다. origin 은 반구의 기하 원점이고(천장과 domeTop 이 여기서 나온다)
		// sweepFloor 는 훑기 바닥이다 — 바닥만 네 칸 아래다. 까닭은 sweepFloorY 에 있다.
		int origin = domeOriginY(center);
		int sweepFloor = sweepFloorY(center);
		int ceiling = origin + Mth.floor(RADIUS);
		int minX = Mth.floor(center.x - RADIUS);
		int maxX = Mth.floor(center.x + RADIUS);
		int minZ = Mth.floor(center.z - RADIUS);
		int maxZ = Mth.floor(center.z + RADIUS);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int broken = 0;

		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
				LevelChunk chunk = end.getChunkSource().getChunkNow(chunkX, chunkZ);
				// 올라오지 않은 청크는 건너뛴다. 억지로 불러오면 지형을 새로 만든다.
				if (chunk == null || !holdsBlocksAbove(chunk, sweepFloor, ceiling)) {
					continue;
				}
				broken += sweepChunk(end, chunk, chunkX, chunkZ, center, origin, sweepFloor,
						ceiling, minX, maxX, minZ, maxZ, at, MAX_BREAKS_PER_SWEEP - broken);
				if (broken >= MAX_BREAKS_PER_SWEEP) {
					return broken;
				}
			}
		}
		return broken;
	}

	/**
	 * 이 청크가 <b>훑기 바닥</b> 위에 블록을 하나라도 들고 있는가.
	 * <b>{@code hasOnlyAir()} 한 줄이다.</b>
	 *
	 * <p>{@code TrialDryWorld.evaporate} 가 {@code hasFluid()} 로 같은 일을 하고, 그쪽에
	 * 「120만 칸을 392번으로 줄이는 한 줄」이라고 적혀 있다.
	 *
	 * <p>⚠ <b>바닥은 {@link #sweepFloorY} 를 받는다. 기하 원점이 아니다.</b> 원점을 받으면 원점
	 * 아래 네 칸이 든 구획을 「빈 구획」으로 오판해 <b>표면에 쌓은 발판을 통째로 놓친다</b> —
	 * 이 네 칸을 보려고 2026-10-01 에 바닥을 내린 것이라 그것이 곧 작업의 취소다.
	 *
	 * <p>⚠ <b>여기서는 그만큼 벌지 못한다.</b> 구획은 16칸 두께라 훑기 바닥을 담은 구획에는
	 * <b>바닥 아래의 섬 돌</b>이 들어 있어 거의 언제나 참이 된다. 실제로 일하는 거름망은
	 * 열마다의 하이트맵이다(클래스 설명의 「성능」). 그래도 이 물음을 남겨 두는 까닭은 <b>훑기
	 * 바닥이 구획 경계에 맞는 판</b>과 <b>섬 바깥 청크</b>에서는 256열을 한 번에 건너뛰기 때문이고,
	 * 값이 세 번의 비교뿐이라 잃는 것이 없다.
	 */
	private static boolean holdsBlocksAbove(LevelChunk chunk, int sweepFloor, int ceiling) {
		int minSectionY = sweepFloor >> 4;
		int maxSectionY = ceiling >> 4;
		for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
			int index = chunk.getSectionIndexFromSectionY(sectionY);
			if (index < 0 || index >= chunk.getSections().length) {
				continue;
			}
			LevelChunkSection section = chunk.getSection(index);
			if (section != null && !section.hasOnlyAir()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 청크 하나를 훑는다.
	 *
	 * <p>⚠ <b>높이 둘을 함께 받는다.</b> {@code origin} 은 반구의 기하 원점이라 {@link #domeTop}
	 * 에만 들어가고, 열의 아래끝은 {@code sweepFloor} 다. 둘을 섞으면 둘 중 하나가 깨진다 —
	 * {@code origin} 을 아래끝으로 쓰면 표면에 쌓은 발판이 살고, {@code sweepFloor} 를
	 * {@link #domeTop} 에 넣으면 <b>반구가 네 칸 내려앉아 눈에 보이는 모양이 바뀐다.</b>
	 *
	 * @param origin     반구의 기하 원점(= 드래곤 발밑). 천장 계산에만 쓴다
	 * @param sweepFloor 훑기 바닥. 하이트맵 거름망과 열의 아래끝이 이것이다
	 * @param budget     아직 부술 수 있는 블록 수
	 * @return 이 청크에서 부순 수
	 */
	private static int sweepChunk(ServerLevel end, LevelChunk chunk, int chunkX, int chunkZ,
			Vec3 center, int origin, int sweepFloor, int ceiling,
			int minX, int maxX, int minZ, int maxZ,
			BlockPos.MutableBlockPos at, int budget) {
		int broken = 0;
		for (int localX = 0; localX < 16 && broken < budget; localX++) {
			int x = (chunkX << 4) + localX;
			if (x < minX || x > maxX) {
				continue;
			}
			for (int localZ = 0; localZ < 16 && broken < budget; localZ++) {
				int z = (chunkZ << 4) + localZ;
				if (z < minZ || z > maxZ) {
					continue;
				}
				// 칸 가운데로 잰다. 표식과 판정이 칸 가운데를 쓰는 이 저장소의 규약과 같다.
				double flat = flatSquared(center, x, z);
				if (flat > RADIUS * RADIUS) {
					continue;
				}
				// ⚠ 여기가 3만 칸을 2천 번으로 줄이는 한 줄이다. 맨 위 블록이 훑기 바닥보다
				// 낮으면 이 열에는 부술 것이 하나도 없다. 맨손 아레나에서는 섬 표면이
				// sweepFloor − 1 이라 모든 열이 여기서 걸러진다(클래스 설명의 「성능」).
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				if (top < sweepFloor) {
					continue;
				}
				// 천장은 origin 으로 잰다(반구 모양) · 아래끝은 sweepFloor 다(훑는 범위).
				// domeTop 은 최소가 origin 이고 origin = sweepFloor + PODIUM_PILLAR_HEIGHT 이므로
				// highest 가 바닥보다 낮아지는 일은 없다. top 도 위에서 걸러 sweepFloor 이상이다.
				broken += sweepColumn(end, chunk, sweepFloor, Math.min(top,
						Math.min(ceiling, domeTop(origin, flat))), x, z, at, budget - broken);
			}
		}
		return broken;
	}

	/**
	 * 열 하나를 <b>위에서 아래로</b> 부순다.
	 *
	 * <p>위에서부터인 것은 천장에 걸려 중간에 멈추더라도 <b>남는 것이 아래쪽</b>이어야 하기
	 * 때문이다. 아래에서 올라가면 지붕을 남기고 기둥만 갈아 내 「블록으로 숨는 것」을 그대로 둔다.
	 *
	 * <p><b>읽는 것은 청크에게, 쓰는 것은 월드에게</b> 묻는다. 청크는 이미 손에 있으므로 읽을
	 * 때마다 다시 찾을 이유가 없고({@code TrialEnderPulse.Ground} 가 청크 한 칸을 기억하는 것과
	 * 같은 까닭이다), 지우는 것은 하이트맵·빛·클라이언트 갱신을 함께 돌려야 하므로 월드를 지나야
	 * 한다. 지우면 하이트맵이 그 자리에서 내려가지만 <b>{@code highest} 는 이미 받아 둔 값</b>이라
	 * 흔들리지 않는다 — 맨 위는 내려가기만 하므로 한 번 읽어 둔 값이 늘 위쪽이다.
	 *
	 * <p>⚠ {@code highest} 가 {@code sweepFloor} 보다 낮아도 <b>루프가 한 번도 안 돌 뿐</b>이라
	 * 안전하다(빈 범위다). 실제로는 부르는 쪽이 그런 값을 만들지 않는다 — 근거는
	 * {@link #sweepChunk} 의 주석에 적어 두었다.
	 *
	 * @param sweepFloor 열의 아래끝. <b>기하 원점이 아니라 훑기 바닥</b>이다
	 * @param highest    이 열에서 부술 수 있는 가장 높은 칸
	 * @return 부순 수
	 */
	private static int sweepColumn(ServerLevel end, LevelChunk chunk, int sweepFloor,
			int highest, int x, int z, BlockPos.MutableBlockPos at, int budget) {
		int broken = 0;
		int puffAt = Integer.MIN_VALUE;
		for (int y = highest; y >= sweepFloor && broken < budget; y--) {
			at.set(x, y, z);
			BlockState state = chunk.getBlockState(at);
			// 2026-10-01 부터 아래 네 칸에 포디움(기반암 기둥·테두리·엔드 포털)이 들어온다.
			// 여기서 걸러진다 — 넷 다 DRAGON_IMMUNE 이다(클래스 설명의 표).
			if (!breakable(state)) {
				continue;
			}
			// 드롭 없음. 사람이 「아니 파괴」라고 정했고, 바닐라 드래곤도 같은 줄을 쓴다.
			// destroyBlock 이 아닌 까닭은 클래스 설명에 있다 — 증강의 채굴 효과가 딸려 돈다.
			end.removeBlock(at, false);
			broken++;
			if (puffAt == Integer.MIN_VALUE) {
				puffAt = y;
			}
		}
		if (puffAt != Integer.MIN_VALUE) {
			puff(end, x, puffAt, z);
		}
		// center 를 안 받는다. 반구 천장은 부르는 쪽이 이미 highest 에 넣어 두었다.
		return broken;
	}

	/**
	 * 먼지 연출 하나. <b>열마다 하나뿐이다.</b>
	 *
	 * <p>{@code LevelEvent.PARTICLES_DRAGON_BLOCK_BREAK}(2008) 은 바닐라 드래곤이
	 * {@code checkWalls} 끝에서 <b>한 번</b> 쏘는 바로 그 사건이다. 블록마다 쏘면 한 훑기에
	 * 수백 꾸러미가 되고(블록 갱신은 바닐라가 구획 단위로 묶어 주지만 이것은 안 묶인다), 한
	 * 훑기에 하나만 쏘면 스물다섯 칸이 소리 없이 사라진다. 열마다 하나가 그 사이다 — 지붕이
	 * 갈려 나가는 모습이 열 수만큼 보이고 개수는 {@link #MAX_BREAKS_PER_SWEEP} 아래다.
	 */
	private static void puff(ServerLevel end, int x, int y, int z) {
		end.levelEvent(LevelEvent.PARTICLES_DRAGON_BLOCK_BREAK, new BlockPos(x, y, z), 0);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * ⚠ <b>반구의 기하 원점.</b> 천장({@link #domeTop} · {@code ceiling})이 <b>전부 이 값에서</b>
	 * 나온다. <b>훑기 바닥은 이것이 아니다</b> — {@link #sweepFloorY} 를 볼 것.
	 *
	 * <p>⚠⚠ <b>여기를 내리면 반구가 통째로 내려앉는다.</b> 2026-10-01 에 「훑는 바닥만」 네 칸
	 * 내렸는데, 그때 이 값을 함께 내리면 <b>눈에 보이는 반구 모양이 바뀐다</b>(천장이 네 칸
	 * 낮아진다). 그래서 두 뜻을 이름으로 갈랐다 — 전에는 {@code baselineY} 하나가 둘을 겸했다.
	 *
	 * <p>이 값은 <b>드래곤을 못박아 둔 자리의 {@code y}</b> 이고, 그것이 곧
	 * <b>「중앙 기반암 포디움 구조물 맨 위의 바로 위」</b>다.
	 *
	 * <h2>왜 같은 값인가 — 26.3 에서 확인했다</h2>
	 *
	 * <p>{@code DragonLastStand.podium()} 이 자리를 이렇게 잡는다.
	 *
	 * <pre>Vec3.atBottomCenterOf(end.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES,
	 *         EnderDragonFight.getPodiumLocation(dragon.getFightOrigin())))</pre>
	 *
	 * <p>그 XZ 의 맨 위 블록은 <b>포디움 중앙의 기반암 기둥</b>이다.
	 * {@code EndPodiumFeature.place} 가 포디움 원점 {@code p} 에서
	 * {@link #PODIUM_PILLAR_HEIGHT}(= 4)만큼 {@code p.above(0..3)} 에 기반암을 세우므로 맨 위
	 * 블록이 {@code p.y + 3} 이고, 하이트맵은 <b>그 위의 첫 빈 칸</b>인 {@code p.y + 4} 를
	 * 돌려준다. 곧
	 *
	 * <blockquote><b>기하 원점 = 포디움 원점 {@code p.y} + 4 = 구조물 맨 위 블록의
	 * <u>바로 위</u></b></blockquote>
	 *
	 * <p>이고, 드래곤의 발이 정확히 그 높이에 있으므로 읽는 법이 하나 더 있다 —
	 * <b>「드래곤 발밑」</b>.
	 *
	 * <p>⚠ <b>포디움 원점의 {@code y} 를 직접 읽을 수는 없다.</b>
	 * {@code EnderDragonFight.getPodiumLocation} 은 {@code origin} 에 {@code BlockPos.ZERO} 를
	 * 더한 것이라 {@code y} 가 0 이고, 포디움의 진짜 높이는 바닐라도 그 자리에서 하이트맵으로
	 * 찾는다({@code EnderDragonFight} 바이트코드에서 확인했다). 그래서 하이트맵으로 잡은
	 * 드래곤의 자리가 <b>이 값을 얻는 유일한 길</b>이고, {@link #sweepFloorY} 가 거기서 4 를
	 * 되돌려 포디움 원점을 되찾는다.
	 *
	 * <p>⚠⚠ <b>남아 있는 구멍 하나를 적어 둔다.</b> 이 값이 {@code DragonLastStand.podium()} 의
	 * 하이트맵에서 나오므로, 누가 <b>진입 전에 포디움 정중앙(한 칸)에 탑을 쌓아 두면</b> 기하
	 * 원점이 그 탑 위로 올라가 이 규칙이 그만큼 위만 본다(훑기 바닥도 함께 올라간다). 다만 그
	 * 탑은 <b>드래곤이 앉는 자리 자체</b>를 올리므로 안전지대 중심·부채꼴 꼭대기·검은 원까지
	 * 함께 올라가고, 그것은 이 페이즈가 전부터 들고 있던 성질이다
	 * ({@code DragonLastStand.podium} 이 하이트맵을 쓰는 것이 바닐라 착지 목표와 같은 계산이기
	 * 때문이다). <b>여기서 고치면 다섯 자리가 서로 다른 중심을 쓰게 되므로 고치지 않았다</b> —
	 * 막으려면 {@code podium()} 쪽을 봐야 한다.
	 */
	static int domeOriginY(Vec3 center) {
		return Mth.floor(center.y);
	}

	/**
	 * ⚠ <b>훑기 바닥.</b> 이보다 <b>낮은</b> 칸은 한 번도 읽지 않는다. <b>기하 원점보다 네 칸
	 * 아래</b>다({@link #domeOriginY} {@code −} {@link #PODIUM_PILLAR_HEIGHT}).
	 *
	 * <p>곧 훑는 범위는 <b>반구에 바닥 쪽 네 칸 두께가 더해진 꼴</b>이다. 반구의 모양(천장)은
	 * {@link #domeOriginY} 가 그대로 들고 있으므로 <b>눈에 보이는 것은 달라지지 않고</b>, 가장자리
	 * 에서도 이 네 칸은 열린다({@link #domeTop} 의 최솟값이 기하 원점이다).
	 *
	 * <h2>사람이 2026-10-01 에 「내린다」고 답했다</h2>
	 *
	 * <p>전에 이 자리에는 <b>「섬 표면에서 세 칸까지 쌓은 발판은 이 규칙이 부수지 않는다 — 값을
	 * 바꾸고 싶으면 여기가 아니라 사람에게 물을 것」</b>이라고 적혀 있었다. <b>물었고, 사람이
	 * 「막는다」고 답했다.</b> 그래서 훑기 바닥만 네 칸 내렸다 — 그 세 칸이 바로 아래 셈의
	 * {@code p.y}~{@code p.y + 2} 이고, 새 바닥이 {@code p.y} 이므로 이제 <b>첫 칸부터 부순다.</b>
	 *
	 * <h2>새 계산 — 왜 하필 네 칸인가</h2>
	 *
	 * <p>{@link #domeOriginY} 의 셈을 거꾸로 읽는다. 기하 원점이 포디움 원점 {@code p} 에서
	 * {@code p.y + 4} 이므로
	 *
	 * <blockquote><b>훑기 바닥 = 기하 원점 − 4 = {@code p.y}</b></blockquote>
	 *
	 * <p>이고, {@code EnderDragonFight} 가 그 {@code p} 를 <b>하이트맵</b>으로 잡으므로
	 * <b>{@code p.y} 는 「섬 표면 위의 첫 빈 칸」</b>이다. 곧 새 바닥은 <b>사람이 섬에 서서 놓는
	 * 첫 블록의 높이</b>와 정확히 같다 — 표면에 쌓은 발판·지붕이 첫 칸부터 범위에 든다.
	 *
	 * <p>4 를 숫자로 적지 않고 {@link #PODIUM_PILLAR_HEIGHT}(=
	 * {@code EndPodiumFeature.PODIUM_PILLAR_HEIGHT}, 바닐라 상수를 직접 참조한다)로 쓴 까닭은
	 * 그 상수에 적어 두었다.
	 *
	 * <h2>⚠ 이 값이 지키는 두 성질 — 만지기 전에 둘 다 읽을 것</h2>
	 *
	 * <ol>
	 *   <li><b>섬 표면을 한 번도 읽지 않는다.</b> 섬 표면의 맨 위 블록은 {@code p.y − 1} 이므로
	 *       <b>바닥보다 한 칸 아래</b>다. 남은 여유가 한 칸이라 <b>더 내리면 바닥을 읽기
	 *       시작한다</b>(그때도 {@code end_stone} 이 {@code DRAGON_IMMUNE} 이라 부서지지는 않지만,
	 *       겹이 하나로 줄고 아래 ②가 죽는다)</li>
	 *   <li><b>하이트맵 거름망이 맨손 아레나에서 모든 열을 거른다.</b> {@link #sweepChunk} 의
	 *       {@code top < sweepFloor} 가 {@code (p.y − 1) < p.y} 로 <b>참</b>이다. 이 성질이 이
	 *       클래스 성능의 전부다(클래스 설명의 「성능」)</li>
	 * </ol>
	 *
	 * <p>⚠ 새 범위에는 <b>포디움 구조물이 들어온다</b>(기반암 기둥 {@code p.y..p.y + 3} · 테두리
	 * 기반암 {@code p.y} · 엔드 포털 {@code p.y}). 전부 {@code DRAGON_IMMUNE} 이라 한 칸도 안
	 * 부서진다 — 목록과 근거는 클래스 설명의 표에 있고, 거기 <b>지켜지지 않는 것(벽 횃불 넷)</b>도
	 * 함께 적어 두었다.
	 */
	static int sweepFloorY(Vec3 center) {
		return domeOriginY(center) - PODIUM_PILLAR_HEIGHT;
	}

	/** 칸 가운데에서 잰 수평 거리의 제곱. */
	static double flatSquared(Vec3 center, int x, int z) {
		double dx = x + 0.5 - center.x;
		double dz = z + 0.5 - center.z;
		return dx * dx + dz * dz;
	}

	/**
	 * 그 열에서 반구가 덮는 가장 높은 칸.
	 *
	 * <p>{@code dx² + dz² + (y − 원점)² ≤ R²} 이므로 {@code y ≤ 원점 + √(R² − 수평²)} 이다.
	 * <b>기둥이 아니라 반구</b>인 것이 이 한 줄이다 — 이것을 빼면 가장자리에서도 25칸 높이까지
	 * 부수는 원기둥이 되고, 사람이 정한 것은 「반구형태로」다.
	 *
	 * <p>⚠ <b>받는 것은 {@link #domeOriginY} 다. {@link #sweepFloorY} 를 넣지 마라.</b> 2026-10-01
	 * 에 훑기 바닥을 네 칸 내렸지만 <b>반구의 기하는 원점 그대로</b>다 — 바닥을 여기 넣으면 천장이
	 * 함께 네 칸 내려앉아 <b>눈에 보이는 반구 모양이 바뀐다.</b> 시험이
	 * {@code 반구_천장은_그대로다} 로 이 값을 못박고 있다.
	 *
	 * <p>돌려주는 값은 <b>언제나 원점 이상</b>이다(반구 밖에서도 원점을 돌려준다). 곧 훑기 바닥
	 * ({@code = 원점 − 4})보다 늘 네 칸 위이므로 {@link #sweepColumn} 의 범위가 비는 일이 없다.
	 */
	static int domeTop(int domeOriginY, double flatSquared) {
		double height = RADIUS * RADIUS - Math.max(0.0, flatSquared);
		if (!(height > 0.0)) {
			return domeOriginY;
		}
		return domeOriginY + Mth.floor(Math.sqrt(height));
	}

	/**
	 * 이 블록을 부숴도 되는가. <b>바닐라 {@code EnderDragon.checkWalls} 와 같은 세 물음이다.</b>
	 *
	 * <p>{@code DRAGON_IMMUNE} 이 이 저장소의 「부수면 안 되는 것」 목록을 <b>대신 들고 있다</b> —
	 * 클래스 설명의 표를 볼 것. 우리 목록을 따로 적으면 판이 올라 바닐라가 한 줄 더할 때
	 * 우리만 뒤처진다.
	 */
	static boolean breakable(BlockState state) {
		return !state.isAir()
				&& !state.is(BlockTags.DRAGON_TRANSPARENT)
				&& !state.is(BlockTags.DRAGON_IMMUNE);
	}

	// ------------------------------------------------------------------ 시험이 세는 값

	/**
	 * 한 번의 훑기에서 하이트맵을 읽는 <b>횟수</b>. 곧 반구가 덮는 열의 수다.
	 *
	 * <p>값에서 직접 센다 — 반경을 올리는 사람이 성능을 눈으로 세지 않아도 시험이 먼저 멈춰
	 * 세운다.
	 */
	static int columnProbesPerSweep(double centerX, double centerZ) {
		int minX = Mth.floor(centerX - RADIUS);
		int maxX = Mth.floor(centerX + RADIUS);
		int minZ = Mth.floor(centerZ - RADIUS);
		int maxZ = Mth.floor(centerZ + RADIUS);
		Vec3 center = new Vec3(centerX, 0.0, centerZ);
		int count = 0;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (flatSquared(center, x, z) <= RADIUS * RADIUS) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * 한 번의 훑기에서 {@code hasOnlyAir()} 를 묻는 <b>횟수</b>. 청크 수 × 구획 수다.
	 *
	 * <p>{@code TrialDryWorld} 의 「청크 49개 × 구획 8개 = 392번」과 같은 자리에 같은 근거로 있는
	 * 값이다.
	 *
	 * <p>⚠ <b>받는 것은 기하 원점이고, 구획 범위의 아래끝은 훑기 바닥에서 잰다</b>
	 * ({@code (원점 − 4) >> 4} 부터 {@code (원점 + 25) >> 4} 까지). {@link #holdsBlocksAbove} 가
	 * 실제로 도는 범위와 <b>같은 셈이라야</b> 이 값이 성능의 증거가 된다 — 2026-10-01 에 바닥을
	 * 내리면서 여기도 함께 내렸다. 원점 68 에서는 구획 수가 그대로 2개지만(64와 93이 같은 두
	 * 구획에 든다) 원점 66 에서는 3개가 된다.
	 *
	 * @param domeOriginY 반구의 기하 원점(= {@link #domeOriginY}). 훑기 바닥이 아니다
	 */
	static int sectionProbesPerSweep(double centerX, double centerZ, int domeOriginY) {
		int minX = Mth.floor(centerX - RADIUS);
		int maxX = Mth.floor(centerX + RADIUS);
		int minZ = Mth.floor(centerZ - RADIUS);
		int maxZ = Mth.floor(centerZ + RADIUS);
		int chunks = ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
		int sweepFloor = domeOriginY - PODIUM_PILLAR_HEIGHT;
		int sections = (((domeOriginY + Mth.floor(RADIUS)) >> 4) - (sweepFloor >> 4)) + 1;
		return chunks * sections;
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. <b>월드를 만지지 않는다</b> — 칸 하나를 비우는 것이 전부라
	 * {@code SERVER_STOPPED} 에서 불려도 터질 것이 없다.
	 *
	 * <p>⚠ <b>부순 블록은 되돌리지 않는다.</b> 되살리려면 「우리가 지운 것」을 기억해야 하는데 그
	 * 기억은 서버 재시작 한 번으로 어긋나고, 어긋난 기억은 남의 건축 위에 블록을 놓는다 —
	 * {@code TrialDryWorld.clearState} 와 {@code TrialCrystalGuard.clearState} 가 같은 판단을
	 * 같은 근거로 적어 두었다.
	 */
	static void clearState() {
		lastSweep = Long.MIN_VALUE;
	}

	/** 시험이 들여다보는 곳. 훑기 시계가 돌고 있는가. */
	static boolean sweeping() {
		return lastSweep != Long.MIN_VALUE;
	}

	/** 로그 한 줄. {@code DragonLastStand.enter} 가 진입할 때 한 번 남긴다. */
	static void logEntry(MinecraftServer server) {
		SharedFateMod.LOGGER.info(
				"[END] 반구 블록 파괴가 돕니다 — 반경 {}칸 · {}틱마다 · 한 번에 최대 {}칸 · "
						+ "mobGriefing={}",
				RADIUS, SWEEP_TICKS, MAX_BREAKS_PER_SWEEP,
				server.getGameRules().get(GameRules.MOB_GRIEFING));
	}
}
