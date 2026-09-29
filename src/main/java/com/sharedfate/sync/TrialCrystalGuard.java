package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link TrialCatalog.Risk.CrystalGuard} 를 돌린다 — 크리스탈을 깨기 어렵게 만드는 축.
 *
 * <h2>이 카드는 피해를 주지 않는다</h2>
 *
 * <p>같은 풀의 카드가 전부 「맞으면 아프다」면 룰렛이 무엇을 뽑든 같은 판이 된다. 이 카드가
 * 빼앗는 것은 <b>시간</b>이다. 크리스탈이 오래 살아 있을수록 드래곤은 계속 회복하므로, 한 대도
 * 때리지 않고도 전투가 길어진다. <b>여기에 피해를 더하지 말 것</b> — 더하는 순간 이 축이
 * 사라지고 카드 둘이 서로 베낀 것이 된다.
 *
 * <h2>세 갈래는 서로 독립이다</h2>
 *
 * <ul>
 *   <li>{@code arrowImmune} — {@link CrystalWatch} 의 깃발을 세운다. 실제로 막는 일은
 *       {@code EndCrystalGuardMixin} 이 크리스탈 쪽에서 한다.</li>
 *   <li>{@code restoreCage} — 남은 크리스탈에 쇠창살 우리를 다시 세운다.</li>
 *   <li>{@code digSlowdown} — 팀 전원에게 채굴 피로 I.</li>
 * </ul>
 *
 * <p>카드 둘이 각각 {@code (true,false,false)} 「크리스탈 보호막」과
 * {@code (false,true,true)} 「쇠창살과 무딘 곡괭이」다. 갈래를 값으로 두었으므로 셋을 섞은
 * 셋째 카드는 {@code TrialCatalog} 에 줄 하나를 더 적는 일이 된다.
 *
 * <h2>깃발을 되돌리지 않으면 다음 월드까지 샌다</h2>
 *
 * <p>{@link CrystalWatch} 는 정적이라 월드보다 오래 산다. {@code arrowImmune} 을 켠 채 전투가
 * 끝나면 <b>다음 판, 심지어 새로 만든 월드의 크리스탈까지 화살에 맞지 않는다.</b> 그래서
 * {@link #clearState()} 는 조건 없이 깃발을 내린다 — 「내가 켰던가?」를 기억하지 않는 것이
 * 일부러다. 기억하는 순간 그 기억이 어긋날 길이 생긴다.
 *
 * <h2>블록을 놓는 것은 월드를 영구히 바꾸는 일이다</h2>
 *
 * <p>쇠창살은 전투가 끝나도 남는다. 그건 괜찮다 — 바닐라에도 원래 우리가 있는 탑이 있다.
 * 괜찮지 <b>않은</b> 것은 남의 것을 덮는 일이라, 놓을 자리가 <b>공기일 때만</b> 놓는다.
 * 기둥의 흑요석도, 사람이 쌓아 올린 발판도, 이미 서 있는 쇠창살도 건드리지 않는다. 덕분에
 * 「부서진 것만 채운다」가 저절로 성립한다.
 *
 * <p>사람이 손에 들고 다니며 터뜨리는 크리스탈에까지 우리를 세우면 아레나가 쇠창살 밭이
 * 되므로, <b>기둥 위의 크리스탈만</b> 고른다. 판별은 {@link #onSpike} 에 적어 두었다.
 *
 * <h2>주기를 두는 이유</h2>
 *
 * <p>매 틱 블록 75칸 × 크리스탈 10개를 훑으면 전투 내내 헛일을 한다. 크리스탈을 세는 엔티티
 * 조회조차 주기 안으로 넣어 두었으므로, 발동 틱이 아닌 틱에는 깃발 한 번 쓰는 것이 전부다.
 */
public final class TrialCrystalGuard {

	// ------------------------------------------------------------------ 못박아 둔 값

	/**
	 * 채굴 피로 등급. <b>0 고정이다 — 절대 올리지 말 것.</b>
	 *
	 * <p>26.3 이 등급별 채굴 속도 공식을 바꿨는데 <b>등급 0 만 두 판이 같다.</b> 등급을 올리면
	 * 26.2 로 만든 카드 설명과 실제 체감이 어긋나고, 그 어긋남은 서버를 띄워 곡괭이를 휘둘러
	 * 봐야 드러난다. 이 카드가 원하는 것은 「곡괭이가 무뎌졌다」이지 「아무것도 못 캔다」가
	 * 아니므로 등급 0 으로 충분하다.
	 */
	static final int DIG_SLOWDOWN_AMPLIFIER = 0;

	/**
	 * 한 번 걸 때의 지속 시간(틱). 10초.
	 *
	 * <p>갱신 주기보다 넉넉히 길어야 한다 — {@link #DIG_REFRESH_INTERVAL} 설명 참고.
	 */
	static final int DIG_SLOWDOWN_TICKS = 200;

	/**
	 * 채굴 피로를 다시 거는 간격(틱). 2초.
	 *
	 * <p><b>지속 시간보다 훨씬 짧아야 한다.</b> 효과가 한 번이라도 끝까지 닳으면 바닐라가
	 * 그것을 제거하고, 다음 갱신에서 다시 붙으면서 <b>화면의 아이콘이 깜빡인다.</b> 남은 시간이
	 * 충분할 때 같은 등급으로 다시 걸면 바닐라 {@code MobEffectInstance.update} 가 시간만
	 * 늘리므로 제거·추가가 일어나지 않고, 따라서 깜빡임도 없다.
	 */
	static final int DIG_REFRESH_INTERVAL = 40;

	/** 우리를 살피는 간격(틱). 2초. 사람이 깨는 속도보다 훨씬 빠르다. */
	static final int CAGE_REPAIR_INTERVAL = 40;

	/**
	 * 우리의 반폭. 중심에서 ±2칸이라 5×5 가 된다.
	 *
	 * <p>바닐라 {@code EndSpikeFeature.placeSpike} 의 {@code -2..2} 와 같은 값이다.
	 */
	static final int CAGE_HALF_WIDTH = 2;

	/**
	 * 우리의 지붕 높이. 바닥(기둥 꼭대기)에서 위로 3칸.
	 *
	 * <p>바닐라의 {@code 0..3} 과 같다. 크리스탈은 바닥에서 1칸 위에 뜬다.
	 */
	static final int CAGE_TOP = 3;

	private TrialCrystalGuard() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱. {@code DragonTrialManager} 가 이 카드를 들고 있는 팀마다 부른다.
	 *
	 * @param dragon  이 카드는 드래곤을 건드리지 않는다. 위험 실행기들이 <b>같은 모양의 진입점</b>을
	 *                갖게 하려고 받아만 둔다 — 실행기마다 인자가 다르면 배선하는 쪽이 갈래마다
	 *                다른 호출을 적게 되고, 그것이 곧 「새 카드를 배선에서 빠뜨리는」 길이다
	 * @param granted 이 카드를 받은 틱. 주기는 <b>월드 시간이 아니라 여기서부터</b> 센다
	 */
	public static void tick(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, long granted, long now,
			TrialCatalog.Risk.CrystalGuard risk) {
		if (end == null || risk == null) {
			return;
		}

		if (risk.arrowImmune()) {
			// 크리스탈이 다 깨진 뒤에도 계속 선언한다. 「부활」 카드가 되살릴 수 있고, 그때
			// 보호막만 사라져 있으면 두 카드가 서로를 지운 꼴이 된다.
			CrystalWatch.setArrowImmune(true);
		}

		boolean cageDue = risk.restoreCage() && refreshesAt(now, granted, CAGE_REPAIR_INTERVAL);
		boolean digDue = risk.digSlowdown() && refreshesAt(now, granted, DIG_REFRESH_INTERVAL);
		if (!cageDue && !digDue) {
			return;
		}

		// 여기서만 엔티티를 훑는다. 발동 틱이 아니면 위에서 이미 돌아갔다.
		List<? extends EndCrystal> crystals =
				end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive);
		if (crystals.isEmpty()) {
			// 전멸 뒤에는 아무 일도 하지 않는다. 지킬 것이 없는데 사람의 곡괭이만 무뎌지면
			// 그건 시간을 빼앗는 것이 아니라 그냥 괴롭히는 것이다.
			return;
		}

		if (cageDue) {
			for (EndCrystal crystal : crystals) {
				restoreCage(end, crystal);
			}
		}
		if (digDue && members != null) {
			for (ServerPlayer member : members) {
				member.addEffect(digSlowdownEffect());
			}
		}
	}

	/**
	 * 월드가 바뀌거나 전투가 끝날 때.
	 *
	 * <p><b>깃발을 조건 없이 내린다.</b> 놓은 쇠창살은 되돌리지 않는다 — 월드에 남은 블록을
	 * 나중에 지우려면 「우리가 놓은 것」을 기억해야 하는데, 그 기억은 서버 재시작 한 번으로
	 * 어긋나고 어긋난 기억은 남의 건축을 지운다. 바닐라에도 우리가 있는 탑이 있으므로 남아도
	 * 이상하지 않다.
	 *
	 * <p>깬 사람 기록은 여기서 지우지 않는다. 그 기록의 주인은 이 카드가 아니라 판 자체라
	 * {@link CrystalWatch#clearState()} 가 맡는다.
	 */
	public static void clearState() {
		CrystalWatch.setArrowImmune(false);
	}

	// ------------------------------------------------------------------ 쇠창살 우리

	/**
	 * 크리스탈 하나에 우리를 세운다. 이미 서 있는 것은 건드리지 않는다.
	 *
	 * <p>바닐라는 <b>우리가 있는 탑과 없는 탑</b>을 섞어 만든다({@code EndSpike.isGuarded}).
	 * 이 카드는 <b>없던 탑에도</b> 세운다 — 그것이 카드 이름이 약속하는 바다.
	 */
	private static void restoreCage(ServerLevel end, EndCrystal crystal) {
		BlockPos base = spikeTop(end, crystal);
		if (base == null) {
			return;
		}
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dx = -CAGE_HALF_WIDTH; dx <= CAGE_HALF_WIDTH; dx++) {
			for (int dz = -CAGE_HALF_WIDTH; dz <= CAGE_HALF_WIDTH; dz++) {
				for (int dy = 0; dy <= CAGE_TOP; dy++) {
					if (!cagePart(dx, dz, dy)) {
						continue;
					}
					at.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
					if (!end.getBlockState(at).isAir()) {
						// 공기가 아니면 남의 것이거나 이미 채워진 것이다. 둘 다 그냥 둔다.
						continue;
					}
					// UPDATE_CLIENTS 만 준다. 이웃 갱신을 돌리면 바닐라 연결 규칙이
					// 우리가 적어 넣은 모양을 다시 계산해 버린다.
					end.setBlock(at, cageState(dx, dz, dy), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/**
	 * 이 크리스탈이 앉아 있는 <b>기둥 꼭대기</b>. 기둥 위가 아니면 {@code null}.
	 *
	 * <p>바닐라가 만드는 모양은 이렇다 — 흑요석이 {@code y < height} 를 채우고,
	 * {@code y == height} 에 <b>기반암</b> 한 칸, 그 위 {@code height + 1} 에 크리스탈이 뜬다.
	 * 우리는 {@code height} 부터 {@code height + 3} 까지 놓인다. 그래서 크리스탈의 한 칸 아래가
	 * 곧 우리의 바닥이다.
	 *
	 * <p>「기반암 아래 흑요석」 두 칸을 함께 보는 이유는 <b>사람이 손으로 놓은 크리스탈</b>을
	 * 걸러 내기 위해서다. 크리스탈은 전투 중에 폭탄으로 쓰는 물건이라, 그것까지 우리로 감싸면
	 * 아레나 바닥이 쇠창살로 뒤덮이고 사람이 갇힌다. 나가는 문의 기반암 위에 놓은 것도 아래가
	 * 흑요석이 아니라 여기서 걸린다.
	 */
	private static @Nullable BlockPos spikeTop(ServerLevel end, EndCrystal crystal) {
		BlockPos base = crystal.blockPosition().below();
		if (!end.getBlockState(base).is(Blocks.BEDROCK)) {
			return null;
		}
		if (!end.getBlockState(base.below()).is(Blocks.OBSIDIAN)) {
			return null;
		}
		return base;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 받은 뒤 흐른 틱이 이 주기의 배수인가. {@code 0} 도 포함한다.
	 *
	 * <p>{@link TrialRisks#firesAt} 와 달리 <b>받은 그 틱에 곧바로 참</b>이다. 그쪽은 예고
	 * 없이 터지는 것을 막으려고 첫 주기를 비우지만, 이 카드는 피해를 주지 않으므로 예고할
	 * 것이 없다. 오히려 늦게 걸리면 「쇠창살이 생긴다」고 읽은 직후 2초 동안 아무 일도 일어나지
	 * 않아 카드가 고장 난 것처럼 보인다.
	 *
	 * <p>주기를 {@code granted} 에서 세는 이유는 {@code TrialRisks} 와 같다 — 월드 시간으로
	 * 세면 카드마다 위상이 겹치고, 복원 직후 {@code now < granted} 일 때 음수로 떨어진다.
	 */
	static boolean refreshesAt(long now, long granted, int interval) {
		if (interval <= 0) {
			return false;
		}
		return TrialRisks.elapsedSinceGrant(now, granted) % interval == 0L;
	}

	/**
	 * 이 자리에 우리 블록이 들어가는가. 중심 기준 상대 좌표다.
	 *
	 * <p>바닐라 {@code EndSpikeFeature.placeSpike} 의 판정 그대로 — <b>네 벽면이거나 지붕</b>
	 * 이면 참이다. 5×5×4 = 100칸 중 안쪽 3×3×3 = 27칸이 비고 73칸이 남는다. 바닥 한가운데
	 * ({@code 0,0,0})가 비는 덕분에 크리스탈을 받치는 기반암을 덮지 않는다.
	 */
	static boolean cagePart(int dx, int dz, int dy) {
		boolean edgeX = Mth.abs(dx) == CAGE_HALF_WIDTH;
		boolean edgeZ = Mth.abs(dz) == CAGE_HALF_WIDTH;
		boolean roof = dy == CAGE_TOP;
		return edgeX || edgeZ || roof;
	}

	/**
	 * 그 자리에 놓을 쇠창살의 <b>연결 모양</b>.
	 *
	 * <p>바닐라와 글자 그대로 같은 식이다. 축이 엇갈려 보이는 것은 맞다 — {@code x = ±2} 벽에
	 * 선 창살은 <b>z 방향으로</b> 이어지므로 북·남이 켜지고, {@code z = ±2} 벽은 그 반대다.
	 * 지붕({@code roof})은 네 방향이 모두 켜져 격자가 된다.
	 *
	 * <p>모양을 직접 적는 이유는 이웃 갱신 없이 놓기 때문이다. 바닐라에 계산을 맡기면 아직
	 * 놓이지 않은 이웃을 보고 끊긴 창살이 된다.
	 */
	static BlockState cageState(int dx, int dz, int dy) {
		boolean roof = dy == CAGE_TOP;
		boolean wallAlongZ = dx == -CAGE_HALF_WIDTH || dx == CAGE_HALF_WIDTH || roof;
		boolean wallAlongX = dz == -CAGE_HALF_WIDTH || dz == CAGE_HALF_WIDTH || roof;
		return Blocks.IRON_BARS.defaultBlockState()
				.setValue(IronBarsBlock.NORTH, wallAlongZ && dz != -CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.SOUTH, wallAlongZ && dz != CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.WEST, wallAlongX && dx != -CAGE_HALF_WIDTH)
				.setValue(IronBarsBlock.EAST, wallAlongX && dx != CAGE_HALF_WIDTH);
	}

	/**
	 * 한 번 걸 채굴 피로.
	 *
	 * <p>인자 셋은 {@code (ambient, showParticles, showIcon)} 이고 이 저장소가 쓰는 조합
	 * {@code (false, false, true)} 그대로다({@code PerkResonantMining.grantHaste} 와 같다).
	 *
	 * <ul>
	 *   <li>{@code ambient = false} — 비컨처럼 「주변에서 받는 것」이 아니다. 참으로 두면
	 *       아이콘 테두리가 흐려져 원인을 짐작하기 어려워진다.</li>
	 *   <li>{@code showParticles = false} — 2초마다 다시 걸리는 효과라 입자를 켜면 전투 내내
	 *       사람 주위에 안개가 낀다. 크리스탈과 드래곤을 봐야 하는 판이다.</li>
	 *   <li>{@code showIcon = true} — <b>아이콘은 켠다.</b> 곡괭이가 갑자기 느려진 이유를
	 *       화면에서 읽을 수 없으면 카드가 아니라 버그로 보인다. 지속 시간이 갱신 주기보다
	 *       훨씬 길어 아이콘이 사라졌다 나타나는 일은 없다.</li>
	 * </ul>
	 */
	static MobEffectInstance digSlowdownEffect() {
		return new MobEffectInstance(MobEffects.MINING_FATIGUE, DIG_SLOWDOWN_TICKS,
				DIG_SLOWDOWN_AMPLIFIER, false, false, true);
	}
}
