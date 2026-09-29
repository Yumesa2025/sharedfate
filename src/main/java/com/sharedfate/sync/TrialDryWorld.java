package com.sharedfate.sync;

import com.sharedfate.inventory.ExpandedInventoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link TrialCatalog.Risk.DryWorld} 실행기 — 메마른 세계.
 *
 * <h2>이 카드가 실제로 빼앗는 것은 설치다</h2>
 *
 * <p>엔드에는 <b>원래 물이 없다.</b> 그러니 「증발」이 지우는 것은 사람이 그 자리에서 놓은
 * 물뿐이고, 카드가 실제로 빼앗는 것은 둘이다.
 *
 * <ul>
 *   <li><b>MLG 물받이</b> — 떨어지면서 물을 놓아 사는 기술. 섬 가장자리와 흑요석 기둥 위에서
 *       목숨줄이다</li>
 *   <li><b>즉석 흑요석</b> — 용암에 물을 부어 발판을 만드는 것</li>
 * </ul>
 *
 * <p>그래서 <b>설치 금지가 이 카드의 본체이고 증발은 연출에 가깝다.</b> 설치를 못 막으면 이
 * 카드는 「양동이 한 번 다시 채우기」로 끝난다. 값을 고치는 사람은 증발 범위보다
 * {@link #banPlacing} 이 덮는 길을 먼저 볼 것.
 *
 * <h2>「한 번」과 「끝까지」를 어떻게 갈랐는가</h2>
 *
 * <p>한 파일에 수명이 다른 두 가지가 들어 있다. 가르는 장치도 둘이다.
 *
 * <ul>
 *   <li><b>한 번</b> — 증발과 양동이 비우기. {@link #DRIED} 에 <b>받은 틱</b>을 넣는 데
 *       성공한 틱에만 돈다. 들어간 뒤로는 몇 번을 불려도 지나가지 않는다</li>
 *   <li><b>끝까지</b> — 설치 금지. {@link #banUntil} 을 <b>매 틱 다시 밀어 둔다.</b> 깃발이
 *       아니라 기한인 것이 일부러다 — 까닭은 바로 아래</li>
 * </ul>
 *
 * <h2>왜 깃발이 아니라 기한인가</h2>
 *
 * <p>✅ <b>분배기가 고쳐졌다.</b> {@code DragonTrialManager} 는 이제 마지막 세션이 닫히는 틱에
 * {@code TrialRisks.clearState()} 를 부른다({@code DragonTrialManager.endTrials}). 이 글은
 * 그 전에 쓰인 것이고, 아래 설명은 <b>기한을 그대로 두는 이유</b>로 읽을 것.
 *
 * <p>고치기 전에는 「켜 두고 {@link #clearState()} 에서 끈다」가 <b>여기서 샜다.</b> 드래곤이
 * 죽으면 {@code tickSessions} 가 세션을 지우고 그 길에서 {@code TrialRisks.clearState()} 를
 * 부르지 않았다. 깃발이면 드래곤을 잡은 뒤에도 월드를 갈아 끼울 때까지 엔드에서 물을 못
 * 놓았다 — 컴파일도 시험도 조용한 사고다.
 *
 * <p>기한이면 <b>우리가 매 틱 밀어 주는 동안만</b> 금지가 산다. 분배기가 우리를 부르지 않게
 * 되는 순간({@link #BAN_GRACE_TICKS} 뒤) 저절로 풀리므로, 분배기가 뒷정리를 빠뜨려도 금지가
 * 전투보다 오래 살지 못한다. {@link #clearState()} 는 그 위에 한 겹 더 덮는 안전장치다.
 * <b>분배기가 고쳐진 지금도 기한을 깃발로 되돌리지 말 것</b> — 서버 강제 종료처럼 분배기가
 * 그 길을 지나지 못하는 경우가 아직 남아 있고, 기한을 두어 잃는 것은 전투가 끝난 뒤의 2초뿐이다.
 *
 * <h2>오버월드를 건드리지 않는다</h2>
 *
 * <p>증발도 금지도 <b>{@link Level#END} 안에서만</b>이다. 팀원은 전투 중에도 오버월드에 있을 수
 * 있고, 거기 지어 둔 물을 지우면 그건 카드가 아니라 사고다. 블록을 만지는 쪽은 분배기가 넘겨준
 * 엔드 월드만 쓰고({@link #evaporate}), 설치를 막는 쪽은 <b>사람이 서 있는 월드의 차원을 직접
 * 확인한다</b>({@link #banPlacing}) — 그 검사를 지우면 오버월드에서도 물을 못 놓게 된다.
 *
 * <h2>양동이는 없애지 않고 비운다</h2>
 *
 * <p>물·용암·서리눈 양동이를 <b>빈 양동이로 바꾼다.</b> 아이템을 통째로 지우면 사람이 잃은 것이
 * 양동이인지 물인지 화면에서 구분할 수 없다. 대상은 카드에 적힌 <b>셋뿐</b>이다 — 물고기가 든
 * 양동이도 물을 놓지만, 비우면 안에 있던 생물이 사라진다. 그쪽은 「비우기」가 아니라
 * <b>설치 금지</b>로 막는다({@link #placesFluid}).
 */
public final class TrialDryWorld {

	// ------------------------------------------------------------------ 못박아 둔 값

	/**
	 * 물과 용암을 찾는 가로 반경(칸). 중앙 {@code (0, ?, 0)} 에서 잰다.
	 *
	 * <p>{@code TrialRisks.ARENA_RADIUS} 는 40 이지만 그것만으로는 모자라다. 바닐라
	 * {@code EndSpikeFeature} 가 흑요석 기둥을 중앙에서 <b>42칸</b> 되는 원 위에 세우고 기둥
	 * 반지름이 최대 3 이라, 기둥 <b>윗면</b>이 45칸까지 나간다. MLG 물받이가 가장 절실한 자리가
	 * 바로 그 기둥 위다. 48 은 거기에 세 칸을 더한 값이다.
	 *
	 * <p>더 넓히지 않는 이유. 엔드 바깥 섬은 중앙에서 1000칸 밖이고 오버월드에서 오는 흑요석
	 * 발판은 {@code (100, 50, 0)} 이다. 전투와 아무 상관 없는 자리까지 훑으면 「한 번만 하는
	 * 일」이 서버를 멈추는 일이 된다. <b>그 두 곳의 물은 이 카드가 지우지 않는다</b> — 알고
	 * 두는 구멍이다.
	 */
	static final int SCAN_RADIUS = 48;
	/**
	 * 훑는 가장 낮은 높이.
	 *
	 * <p>중앙 섬 표면이 {@code y = 63} 근처이고({@code DragonFireBarrage.FALLBACK_GROUND_Y})
	 * 섬 바닥은 그보다 서른 칸쯤 아래다. 0 까지 내려가면 섬 아래 허공에 사람이 쌓아 둔 발판까지
	 * 들어온다. 더 내려가 봐야 아무것도 없는 허공이다.
	 */
	static final int SCAN_MIN_Y = 0;
	/**
	 * 훑는 가장 높은 높이.
	 *
	 * <p>가장 높은 흑요석 기둥이 {@code y = 103} 이고 그 위 크리스탈이 104 다. 127 은 거기에
	 * 사람이 한참 더 쌓아 올릴 여유를 더한 값이고, 마침 청크 구획(16칸) 경계에 딱 맞아
	 * {@code 0..127} 이 구획 여덟 개가 된다.
	 */
	static final int SCAN_MAX_Y = 127;

	/**
	 * 마지막으로 우리를 부른 틱에서 금지를 더 들고 있는 틱 수. 2초.
	 *
	 * <p>분배기는 전투가 사는 동안 <b>매 틱</b> 우리를 부른다. 그러니 여유는 「몇 틱 건너뛰어도
	 * 금지가 깜빡이지 않을」 만큼이면 되고, 반대로 전투가 끝난 뒤 사람이 <b>기다린다고 느끼지
	 * 않을</b> 만큼 짧아야 한다. 드래곤을 잡고 2초 뒤에 물이 다시 놓인다.
	 */
	static final int BAN_GRACE_TICKS = 40;

	/**
	 * 증발하는 틱에 수증기를 띄우는 자리 수의 상한. 곧 그 틱에 나가는 <b>패킷 수</b>다.
	 *
	 * <p>{@link TrialNightHost#FLARE_MAX_MOBS} 와 같은 값이고 까닭도 같다 — 이 저장소가 쓰는 한
	 * 틱 예산은 400 점이고({@code TrialEnderPulse.MAX_POINTS_PER_TICK}) 시련은 쌓이므로 같은 틱에
	 * 남의 고리도 함께 그려진다. 이 카드는 <b>단 한 틱</b>만 그리므로 예산의 한 귀퉁이만 쓴다.
	 *
	 * <p>상한에 걸리면 나머지 물은 표시 없이 사라진다. 그래도 「무슨 일이 일어났다」는 소리와
	 * 채팅 한 줄이 상한과 무관하게 전한다.
	 */
	static final int STEAM_MAX_POINTS = 64;
	/** 한 자리에 띄우는 수증기 입자 수. */
	static final int STEAM_PARTICLES = 6;

	// ------------------------------------------------------------------ 상태

	/**
	 * 증발을 이미 끝낸 카드들. 열쇠는 <b>카드를 받은 틱</b>이다.
	 *
	 * <p>{@link TrialCatalog.Risk.DryWorld} 는 칸이 하나도 없어 값으로는 카드 둘을 구분할 수
	 * 없다. 받은 틱이 남는 유일한 구분이고, 팀 둘이 같은 틱에 이 카드를 받는 일은 룰렛이 팀마다
	 * 따로 도는 이상 사실상 없다. 겹치더라도 결과는 「증발이 한 번만 돈다」라 해롭지 않다 —
	 * 엔드의 물은 어차피 한 벌이다.
	 *
	 * <p>정적인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 판이 바뀌면 지난 판의
	 * 틱 번호가 남으므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Set<Long> DRIED = new HashSet<>();

	/**
	 * 설치 금지가 이 틱까지 살아 있다. 아직 한 번도 걸린 적 없으면 {@link Long#MIN_VALUE}.
	 *
	 * <p>{@code volatile} 인 것은 읽는 쪽이 {@link #tick} 이 아니라 상호작용 사건과 믹스인이라
	 * 호출 경로가 눈에 안 띄기 때문이다. 실제로는 둘 다 서버 스레드에서 도므로 값이 필요해서가
	 * 아니라 <b>여기가 바깥에서 읽히는 칸이라는 표시</b>로 둔다.
	 */
	private static volatile long banUntil = Long.MIN_VALUE;

	private TrialDryWorld() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자({@code dragon})도 받는 것은
	 * {@link TrialRisks} 의 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code now} 를 받아 쓰고 {@code end.getGameTime()} 을 부르지 않는다. 룰렛이 도는 동안
	 * {@link TrialFreeze} 가 판을 멈추면 게임 시각도 멈추는데, 그때 직접 물으면 기한이 얼어붙은
	 * 채로 남는다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 「한 번」을 가르는 열쇠가 이것이다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.DryWorld risk) {
		if (end == null || risk == null) {
			return;
		}
		// 분배기는 server.getLevel(Level.END) 만 넘겨주지만 그 한 줄을 믿지 않는다. 여기서
		// 차원을 안 보면 분배기가 언젠가 다른 월드를 넘기는 날 오버월드의 물이 증발한다.
		if (end.dimension() != Level.END) {
			return;
		}

		// ── 「끝까지」 ──────────────────────────────────────────────────────────
		// 매 틱 기한을 다시 민다. 깃발이 아니라 기한인 까닭은 클래스 설명에 있다.
		// TODO 「최후의 저항」(드래곤 체력 30%)에 들어가면 걸려 있던 시련이 전부 멈춘다. 이
		//      설치 금지가 그 「전부」에 드는지는 아직 정해지지 않았다(사람에게 물어 둔 상태다).
		//      지금은 「끝까지 남는다」다 — 최후의 저항이 아직 없어 부딪힐 곳이 없다. 풀기로
		//      정해지면 고칠 곳은 이 한 줄이다(그 틱에 밀지 않으면 2초 뒤 저절로 풀린다).
		banUntil = now + BAN_GRACE_TICKS;

		// ── 「한 번」 ──────────────────────────────────────────────────────────
		// 넣는 데 성공한 틱이 곧 터지는 틱이다. 「터진 적 있나」를 따로 세지 않으므로 세다가
		// 어긋날 자리가 없다.
		if (!DRIED.add(granted)) {
			return;
		}
		List<Vec3> steam = new ArrayList<>();
		evaporate(end, steam);
		if (members != null && !members.isEmpty()) {
			emptyBuckets(members);
			announce(end, members);
		}
		showSteam(end, steam);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>설치 금지를 반드시 여기서 내린다.</b> 금지가 전투보다 오래 살면 다음 판, 나아가 새로
	 * 만든 월드에서도 양동이를 못 쓰게 된다. 기한이라 시간이 지나면 저절로 풀리지만, 판을
	 * 리셋하는 순간 기한도 <b>그 자리에서</b> 없애야 2초를 기다리지 않는다.
	 *
	 * <p>증발시킨 블록은 되돌리지 않는다. 월드에 없어진 블록을 나중에 되살리려면 「우리가
	 * 지운 것」을 기억해야 하는데, 그 기억은 서버 재시작 한 번으로 어긋나고 어긋난 기억은 남의
	 * 건축 위에 물을 붓는다({@link TrialCrystalGuard#clearState()} 가 쇠창살에서 한 판단과
	 * 같다). 비운 양동이도 마찬가지로 되채우지 않는다.
	 *
	 * <p>월드를 만지지 않는다 — 필드 둘을 비우는 것이 전부라 서버가 이미 내려간 뒤
	 * ({@code SERVER_STOPPED})에 불려도 터질 것이 없다.
	 */
	public static void clearState() {
		DRIED.clear();
		banUntil = Long.MIN_VALUE;
	}

	// ------------------------------------------------------------------ 증발

	/**
	 * 아레나 근처의 물과 용암을 지운다. <b>한 번만 도는 일이다.</b>
	 *
	 * <h2>범위를 가로 {@value #SCAN_RADIUS} · 세로 {@value #SCAN_MIN_Y}~{@value #SCAN_MAX_Y} 로
	 * 한정한다</h2>
	 *
	 * <p>까닭은 {@link #SCAN_RADIUS} · {@link #SCAN_MIN_Y} · {@link #SCAN_MAX_Y} 에 하나씩 적어
	 * 두었다. 엔드 전체를 훑겠다는 생각을 하지 말 것 — 엔드는 사람이 간 만큼 끝없이 늘어나고,
	 * 그 전부를 한 틱에 읽으면 서버가 멈춘다.
	 *
	 * <h2>빈 구획은 읽지도 않는다</h2>
	 *
	 * <p>{@code 97 × 97 × 128} 은 120만 칸이라 그대로 훑으면 한 틱을 통째로 쓴다. 그런데
	 * {@code LevelChunkSection} 은 <b>자기 안의 유체 수를 세고 있다</b>
	 * ({@code fluidCount} / {@code hasFluid}). 물이 한 칸도 없는 구획은 그 한 번의 비교로
	 * 건너뛰므로, 물이 없는 보통의 판에서는 <b>청크 49개 × 구획 8개 = 392번의 비교</b>가 전부다.
	 * 실제로 읽는 4096칸짜리 구획은 사람이 물을 놓은 그곳뿐이다.
	 *
	 * <p>청크는 {@code getChunkNow} 로만 본다. 없으면 건너뛴다 — 안 올라온 청크를 억지로
	 * 올리면 「물을 지우려다 지형을 새로 만드는」 일이 된다.
	 *
	 * @param steam 수증기를 띄울 자리를 담아 갈 통. {@link #STEAM_MAX_POINTS} 까지만 담는다
	 */
	private static void evaporate(ServerLevel end, List<Vec3> steam) {
		int minChunk = -SCAN_RADIUS >> 4;
		int maxChunk = SCAN_RADIUS >> 4;
		int minSectionY = SCAN_MIN_Y >> 4;
		int maxSectionY = SCAN_MAX_Y >> 4;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

		for (int chunkX = minChunk; chunkX <= maxChunk; chunkX++) {
			for (int chunkZ = minChunk; chunkZ <= maxChunk; chunkZ++) {
				LevelChunk chunk = end.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
					int index = chunk.getSectionIndexFromSectionY(sectionY);
					if (index < 0 || index >= chunk.getSections().length) {
						continue;
					}
					LevelChunkSection section = chunk.getSection(index);
					// 여기가 120만 칸을 392번으로 줄이는 한 줄이다.
					if (section == null || !section.hasFluid()) {
						continue;
					}
					dryUpSection(end, section, chunkX, chunkZ, sectionY, at, steam);
				}
			}
		}
	}

	/** 구획 하나(16×16×16)를 훑어 유체를 지운다. 범위 밖 칸은 건너뛴다. */
	private static void dryUpSection(ServerLevel end, LevelChunkSection section, int chunkX,
			int chunkZ, int sectionY, BlockPos.MutableBlockPos at, List<Vec3> steam) {
		for (int localY = 0; localY < 16; localY++) {
			int worldY = (sectionY << 4) + localY;
			// 높이는 층 하나에 한 번만 본다. 구획은 16칸 단위라 범위의 위아래 끝이 구획
			// 한가운데를 자를 수 있다.
			if (worldY < SCAN_MIN_Y || worldY > SCAN_MAX_Y) {
				continue;
			}
			for (int localX = 0; localX < 16; localX++) {
				int worldX = (chunkX << 4) + localX;
				for (int localZ = 0; localZ < 16; localZ++) {
					int worldZ = (chunkZ << 4) + localZ;
					if (!insideScan(worldX, worldY, worldZ)) {
						continue;
					}
					// 구획을 다시 읽는다. 앞선 칸을 지우면서 팔레트가 바뀌었을 수 있다.
					BlockState state = section.getBlockState(localX, localY, localZ);
					BlockState drier = withoutFluid(state);
					if (drier == null) {
						continue;
					}
					at.set(worldX, worldY, worldZ);
					// UPDATE_CLIENTS 만 준다. 이웃 갱신을 돌리면 아직 지우지 않은 옆칸 물이
					// 그 자리로 흐르려고 예약을 쌓는다 — 어차피 이 틱에 전부 지우므로 헛일이고,
					// 물이 많은 판에서는 그 헛일이 곧 한 틱의 정지다.
					end.setBlock(at, drier, Block.UPDATE_CLIENTS);
					if (steam.size() < STEAM_MAX_POINTS) {
						steam.add(new Vec3(worldX + 0.5, worldY + 0.5, worldZ + 0.5));
					}
				}
			}
		}
	}

	/**
	 * 이 블록에서 물·용암을 뺀 모양. 뺄 것이 없으면 {@code null}.
	 *
	 * <p>갈래가 둘인 것은 유체가 블록으로 서 있을 수도 있고 <b>남의 블록 안에 잠겨</b> 있을
	 * 수도 있기 때문이다.
	 *
	 * <ul>
	 *   <li>{@link LiquidBlock} — 물·용암 그 자체다. 공기로 바꾼다</li>
	 *   <li>{@code waterlogged} 를 가진 블록 — 계단·울타리 안에 잠긴 물이다. <b>블록은 그대로
	 *       두고 물만 뺀다.</b> 통째로 지우면 사람이 쌓은 발판이 사라져 그 위에 선 사람이
	 *       떨어진다 — 그건 이 카드가 아니라 사고다</li>
	 * </ul>
	 *
	 * <p>둘 다 아닌 유체 칸(거품 기둥 같은 것)은 그냥 둔다. 물 없이 남으면 바닐라가 스스로
	 * 정리하고, 억지로 건드리면 무엇이 무너질지 우리가 알 수 없다.
	 */
	private static @Nullable BlockState withoutFluid(BlockState state) {
		if (state.getFluidState().isEmpty()) {
			return null;
		}
		if (state.getBlock() instanceof LiquidBlock) {
			return Blocks.AIR.defaultBlockState();
		}
		if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
			return state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
		}
		return null;
	}

	/**
	 * 이 칸이 훑는 범위 안인가. 가로는 원, 세로는 구간이다.
	 *
	 * <p>월드가 없어도 답이 정해지는 계산이라 시험이 직접 부른다.
	 */
	static boolean insideScan(int x, int y, int z) {
		if (y < SCAN_MIN_Y || y > SCAN_MAX_Y) {
			return false;
		}
		return x * x + z * z <= SCAN_RADIUS * SCAN_RADIUS;
	}

	// ------------------------------------------------------------------ 양동이 비우기

	/**
	 * 팀이 들고 있는 물·용암·서리눈 양동이를 빈 양동이로 바꾼다.
	 *
	 * <h2>엔더 상자는 한 번만 비워도 전부 비워진다 — 그래도 전원을 돈다</h2>
	 *
	 * <p>{@code shareEnderChest} 가 켜져 있으면 {@code PlayerEnderChestMixin} 이 팀원 전원의
	 * {@code getEnderChestInventory()} 를 <b>{@code TeamState.enderContainer} 한 벌</b>을 보는
	 * {@link TeamEnderChestView} 로 바꿔치운다. 그러니 한 사람 것을 비우면 나머지도 그 자리에서
	 * 비워진다. 주 인벤토리도 같다 — {@code InventorySwapper.finishJoin} 이 사람의
	 * {@code Inventory.items} 를 {@code TeamState.mainItems} 로 갈아 끼우므로 <b>팀 전체가 한
	 * 벌</b>이다.
	 *
	 * <p>그래도 전원을 도는 것은 <b>그 두 설정이 꺼질 수 있기 때문</b>이다
	 * ({@code SharedFateConfig.shareEnderChest}). 꺼진 판에서 한 사람만 비우면 나머지는 물
	 * 양동이를 그대로 들고 있다. 하는 일이 「이미 빈 양동이를 다시 보는 것」이라 두 번 돌아도
	 * 값이 달라지지 않으므로, 켜진 판에서는 헛일 몇 번이고 꺼진 판에서는 이것만이 정답이다.
	 *
	 * <h2>비우는 자리가 넷이다</h2>
	 *
	 * <p>사람이 물건을 둘 수 있는 곳을 하나라도 빠뜨리면 그 칸에 든 양동이만 살아남는다.
	 *
	 * <ol>
	 *   <li>기본 인벤토리 — {@code Inventory.getContainerSize()} 는 36칸에 장비칸을 더한
	 *       값이고 {@code getItem} 이 <b>보조손까지</b> 함께 본다</li>
	 *   <li>추가 칸 — 이 모드가 붙인 칸이다. 인벤토리와 별개의 통이라 따로 돌지 않으면 샌다</li>
	 *   <li>엔더 상자</li>
	 *   <li><b>커서에 쥔 것</b> — 인벤토리 화면을 열어 둔 채 카드가 터지면 마우스에 붙어 있는
	 *       한 칸은 어느 통에도 들어 있지 않다</li>
	 * </ol>
	 */
	private static void emptyBuckets(List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			emptyContainer(member.getInventory());
			if (ExpandedInventoryManager.enabled()) {
				emptyContainer(ExpandedInventoryManager.extraFor(member));
			}
			emptyContainer(member.getEnderChestInventory());
			ItemStack carried = member.containerMenu.getCarried();
			ItemStack drainedCarried = drained(carried);
			if (drainedCarried != null) {
				member.containerMenu.setCarried(drainedCarried);
			}
			// 바뀐 칸을 곧바로 내려보낸다. 안 그러면 화면에는 물 양동이가 남아 있다가 다음에
			// 그 칸을 만질 때에야 빈 양동이로 바뀐다 — 사람 눈에는 카드가 아니라 버그다.
			member.containerMenu.broadcastChanges();
		}
	}

	/** 통 하나를 훑어 비운다. 바꿀 것이 없으면 아무 일도 하지 않는다. */
	private static void emptyContainer(Container container) {
		boolean changed = false;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack drained = drained(container.getItem(slot));
			if (drained == null) {
				continue;
			}
			container.setItem(slot, drained);
			changed = true;
		}
		if (changed) {
			container.setChanged();
		}
	}

	/**
	 * 이 스택을 비운 모습. 비울 것이 아니면 {@code null}.
	 *
	 * <p>대상은 카드에 적힌 <b>셋뿐</b>이다 — 물·용암·서리눈. 물고기가 든 양동이는 물을 놓지만
	 * 여기서 비우지 않는다. 비우면 안에 있던 생물이 소리 없이 사라져 「양동이를 잃었나 물고기를
	 * 잃었나」가 또 갈리기 때문이다. 그쪽은 {@link #placesFluid} 가 설치를 막는 것으로 끝낸다.
	 *
	 * <p>개수를 그대로 옮긴다. 바닐라 양동이는 한 칸에 하나뿐이지만, 쌓이게 바꾸는 판이 오면
	 * 개수를 1 로 박아 둔 코드가 <b>말없이 물건을 지운다.</b>
	 */
	static @Nullable ItemStack drained(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		if (!stack.is(Items.WATER_BUCKET) && !stack.is(Items.LAVA_BUCKET)
				&& !stack.is(Items.POWDER_SNOW_BUCKET)) {
			return null;
		}
		return new ItemStack(Items.BUCKET, stack.getCount());
	}

	// ------------------------------------------------------------------ 설치 금지

	/**
	 * 지금 이 월드에서 이 아이템으로 놓는 것을 막아야 하는가.
	 *
	 * <p><b>이 카드의 본체가 이 한 함수다.</b> 붙는 자리가 셋이라(주손·보조손·디스펜서) 판단을
	 * 한곳에 모아 두었다 — 자리마다 조건을 따로 적으면 <b>반드시 한쪽만 새는 날이 온다.</b>
	 *
	 * <p>{@code level.getGameTime()} 을 여기서 직접 묻는다. {@link #tick} 이 {@code now} 를
	 * 쓰는 것과 어긋나 보이지만 <b>같은 시계다</b> — 기한은 엔드의 게임 시각으로 적혔고, 여기는
	 * 차원이 엔드일 때만 지나므로 재는 쪽도 같은 엔드의 시각이다. 판이 얼면 양쪽이 함께 멈추니
	 * 얼어 있는 동안 금지가 저절로 풀리는 일도 없다.
	 *
	 * @param level 사람이 서 있는(또는 디스펜서가 놓인) 월드. <b>여기서 차원을 보는 것이
	 *              오버월드를 지키는 유일한 장치다</b>
	 * @param item  놓으려는 아이템
	 */
	public static boolean banPlacing(@Nullable Level level, @Nullable Item item) {
		if (level == null || level.isClientSide() || level.dimension() != Level.END) {
			return false;
		}
		if (!banHolds(level.getGameTime(), banUntil)) {
			return false;
		}
		return placesFluid(item);
	}

	/**
	 * 금지가 이 틱에 아직 살아 있는가. 월드 없이 답이 정해지므로 시험이 직접 부른다.
	 *
	 * <p>{@code until} 이 {@link Long#MIN_VALUE} 면 한 번도 걸린 적이 없다.
	 */
	static boolean banHolds(long now, long until) {
		return until != Long.MIN_VALUE && now < until;
	}

	/**
	 * 이 아이템이 물·용암·서리눈을 <b>놓는</b> 물건인가.
	 *
	 * <h2>26.3 에서 실제로 세어 본 결과다</h2>
	 *
	 * <p>「물 양동이·용암 양동이·서리눈 양동이」만 막으면 샌다. {@code Items} 를 직접 뜯어
	 * 확인한 26.3 의 양동이 열두 개는 이렇게 갈린다.
	 *
	 * <ul>
	 *   <li><b>{@code BucketItem} + 내용물이 빈 유체가 아님</b> — {@code water_bucket},
	 *       {@code lava_bucket}, 그리고 <b>{@code MobBucketItem} 여섯 개</b>
	 *       ({@code cod} · {@code salmon} · {@code pufferfish} · {@code tropical_fish} ·
	 *       {@code axolotl} · {@code tadpole}). 물고기 양동이는 이름이 물고기지만
	 *       <b>물을 한 칸 놓는다</b> — 이것이 이 카드가 새는 가장 쉬운 길이다</li>
	 *   <li><b>{@code SolidBucketItem}</b> — {@code powder_snow_bucket} 하나뿐이다. 이쪽은
	 *       유체가 아니라 블록을 놓으므로 위 조건에 걸리지 않는다</li>
	 *   <li><b>막지 않는 것</b> — {@code bucket}(빈 것)은 내용물이
	 *       {@link Fluids#EMPTY} 라 저절로 빠지고, {@code milk_bucket} 은 양동이 종류가 아예
	 *       아니다. <b>{@code sulfur_cube_bucket}</b> 은 26.3 에 새로 생긴 것인데
	 *       {@code MobBucketItem} 이면서 내용물이 {@link Fluids#EMPTY} 다 — 생물만 놓고 물은
	 *       놓지 않는다. 「{@code MobBucketItem} 이면 막는다」로 적었으면 이 하나를 잘못
	 *       막았을 것이다. 그래서 종류가 아니라 <b>내용물</b>을 본다</li>
	 * </ul>
	 */
	static boolean placesFluid(@Nullable Item item) {
		if (item == null) {
			return false;
		}
		if (item instanceof SolidBucketItem) {
			return true;
		}
		return item instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY;
	}

	/**
	 * {@code UseItemCallback.EVENT} 에 붙는 지점 — <b>손에 든 물·용암·물고기 양동이</b>를 막는다.
	 *
	 * <p>{@code BucketItem} 은 {@code useOn} 이 아니라 {@code use} 에서 스스로 시선을 쏘아
	 * 놓을 자리를 찾는다. 그래서 블록에 대고 눌러도 {@code ServerPlayerGameMode.useItemOn} 은
	 * 그냥 지나가고 <b>{@code useItem} 이 뒤따라 온다</b> — 이 사건이 그 자리다. 사건이
	 * {@code hand} 를 주므로 <b>주손과 보조손이 한 줄로 함께 막힌다.</b>
	 *
	 * <p>{@code InteractionResult.PASS} 를 돌려주면 바닐라가 평소대로 돈다.
	 */
	public static InteractionResult onUseItem(@Nullable Player player, @Nullable Level level,
			@Nullable InteractionHand hand) {
		if (player == null || level == null || hand == null) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!banPlacing(level, held.getItem())) {
			return InteractionResult.PASS;
		}
		fizzle(player);
		return InteractionResult.FAIL;
	}

	/**
	 * {@code ItemEvents.USE_ON} 에 붙는 지점 — <b>손에 든 서리눈 양동이</b>를 막는다.
	 *
	 * <h2>왜 {@code UseBlockCallback} 이 아닌가</h2>
	 *
	 * <p>{@code SolidBucketItem} 은 {@code BlockItem} 이라 {@code useOn} 으로 블록을 놓는다.
	 * 그 자리를 잡는 사건이 둘인데 하나는 쓰면 안 된다.
	 *
	 * <ul>
	 *   <li>{@code UseBlockCallback} — {@code ServerPlayerGameMode.useItemOn} 의 <b>맨 앞</b>
	 *       에서 터진다. 거기서 막으면 <b>블록 자신의 상호작용까지 함께 막힌다</b> — 서리눈
	 *       양동이를 든 채로는 상자도 못 열게 된다</li>
	 *   <li>{@code ItemEvents.USE_ON} — {@code ItemStack.useOn} 이 아이템의 {@code useOn} 을
	 *       부르려는 <b>바로 그 자리</b>를 감싼다. 블록 상호작용은 그보다 앞에서 이미 끝났으므로
	 *       상자는 열리고 서리눈만 안 놓인다</li>
	 * </ul>
	 *
	 * <p>⚠ <b>이 사건은 통과가 {@code null} 이다.</b> {@code PASS} 를 돌려주면 그것이 그대로
	 * {@code useOn} 의 결과가 되어 <b>모든 아이템의 {@code useOn} 이 돌지 않는다.</b> 다른
	 * 사건들과 규약이 반대이므로 고칠 때 반드시 볼 것.
	 */
	public static @Nullable InteractionResult onUseItemOn(@Nullable UseOnContext context) {
		if (context == null) {
			return null;
		}
		if (!banPlacing(context.getLevel(), context.getItemInHand().getItem())) {
			return null;
		}
		Player player = context.getPlayer();
		if (player != null) {
			fizzle(player);
		}
		return InteractionResult.FAIL;
	}

	/**
	 * 막혔다는 신호. 사람에게만, 그 자리에서.
	 *
	 * <p>글자를 띄우지 않는다. 물을 놓는 것은 급할 때 초에 몇 번씩 누르는 동작이라, 막힐 때마다
	 * 한 줄씩 적으면 채팅창이 그 한 사람의 것이 된다. 「물이 닿자마자 증발했다」는 소리와
	 * 수증기로 충분히 읽히고, 무슨 카드 때문인지는 카드를 받을 때 이미 읽었다.
	 */
	private static void fizzle(Player player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 at = player.position();
		level.sendParticles(ParticleTypes.CLOUD, true, false,
				at.x, at.y + player.getBbHeight() * 0.5, at.z, STEAM_PARTICLES, 0.2, 0.2, 0.2, 0.0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
				0.6F, 1.6F);
	}

	// ------------------------------------------------------------------ 알리기

	/**
	 * 카드가 터졌다고 알린다.
	 *
	 * <h2>여기만은 글자를 쓴다</h2>
	 *
	 * <p>이 저장소는 화면 아래 글자(액션바)를 전부 걷어냈고({@link TrialWarning#shout}) 그
	 * 판단을 여기서도 지킨다 — <b>액션바는 쓰지 않는다.</b> 대신 <b>채팅 한 줄</b>을 쓴다.
	 *
	 * <p>카드가 뽑힐 때 뜨는 화면에 설명이 이미 나가는데도 한 줄을 더 쓰는 이유는, 그 화면이
	 * 말하지 않는 일이 하나 일어나기 때문이다 — <b>사람의 인벤토리와 엔더 상자에 있던 양동이가
	 * 그 순간 비워진다.</b> 카드 설명에는 「증발한다」와 「놓을 수 없다」만 적혀 있고, 가진
	 * 물건이 바뀐 것은 소리로도 표식으로도 알릴 수 없다. 그것을 모르면 사람은 나중에 물 양동이를
	 * 꺼내려다 없는 것을 발견하고 <b>모드가 아이템을 먹었다</b>고 읽는다.
	 *
	 * <p>줄은 <b>하나</b>다. 그 이상은 카드 화면과 같은 말을 두 번 하는 것이다.
	 *
	 * <h2>소리는 사람마다 그 자리에서</h2>
	 *
	 * <p>바닐라 소리 사거리는 볼륨 1 이하면 16칸인데 아레나 반경이 40 이라 한 점에서 울리면
	 * 흩어진 팀원에게 닿지 않는다. 그래서 사람마다 자기 자리에서 울린다.
	 *
	 * <p><b>엔드에 있는 사람에게만</b> 울린다. 오버월드에 있는 팀원의 좌표를 엔드 월드에 그대로
	 * 넘기면 아무 상관 없는 자리에서 소리가 난다.
	 *
	 * <p>⚠ <b>지금은 명단 자체가 엔드에 선 사람뿐이다.</b> 분배기가
	 * {@code DragonTrialManager.membersOf} 에서 차원을 자르도록 고쳐졌다(오버월드 팀원이 엔드의
	 * 고리에 맞던 결함). 그래서 아래 {@code member.level() != end} 검사는 이제 통과만 하고, 채팅
	 * 한 줄도 <b>엔드에 있는 사람에게만</b> 간다. 인벤토리는 팀이 함께 쓰므로 오버월드에 있는
	 * 사람의 양동이도 비워지는데 그 사람은 알림을 못 받는다 — 이 카드가 아니라 명단의 문제이므로
	 * 여기서 목록을 따로 만들지 않았다. 검사는 <b>남겨 둔다</b>: 걸러진 목록을 받는다는 보장이
	 * 이 파일 밖에 있다.
	 */
	private static void announce(ServerLevel end, List<ServerPlayer> members) {
		Component line = Component.literal(
				"[시련] 메마른 세계 — 엔드의 물과 용암이 증발했습니다."
						+ " 들고 있던 물·용암·서리눈 양동이가 비었고, 전투가 끝날 때까지"
						+ " 엔드에서는 물·용암·서리눈을 놓을 수 없습니다.");
		// 글자는 차원을 가리지 않고 보내지만 소리는 엔드에 서 있는 사람만 듣는다. 소리를 뒤로
		// 미뤄 모으는 이유는 TrialWarning.playEach 가 한 번 부를 때 씨앗도 하나여서다 — 전원이
		// 같은 변주를 듣는다. 사람마다 playSound 를 부르면 안 되는 까닭은 그쪽 설명에 있다.
		List<ServerPlayer> here = new ArrayList<>(members.size());
		for (ServerPlayer member : members) {
			member.sendSystemMessage(line);
			if (member.level() == end) {
				here.add(member);
			}
		}
		TrialWarning.playEach(end, here, SoundEvents.FIRE_EXTINGUISH, 1.0F, 0.7F);
	}

	/**
	 * 사라진 물 자리마다 수증기를 한 번 띄운다.
	 *
	 * <p><b>긴 형태</b>({@code overrideLimiter = true})로 보낸다. 짧은 형태는 서버에서 32칸에
	 * 잘리는데, 증발은 아레나 어디에서나 일어나고 기둥 위에서도 일어난다 — 되돌리면 <b>가까운
	 * 것만 보이고 나머지는 소리 없이 사라진다.</b>
	 */
	private static void showSteam(ServerLevel end, List<Vec3> steam) {
		for (Vec3 at : steam) {
			end.sendParticles(ParticleTypes.CLOUD, true, false,
					at.x, at.y, at.z, STEAM_PARTICLES, 0.25, 0.25, 0.25, 0.0);
		}
	}
}
