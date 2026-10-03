package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 프리즘 「완충」의 빠져나갈 길 — <b>나뉘어 들어오는 동안 적을 잡으면 남은 몫이 사라진다</b> — 을
 * 본다.
 *
 * <h2>여기서 볼 수 없는 것</h2>
 * <p>몹이 죽는 순간을 잡는 부분({@code SpreadDamageManager.onDeath} 의 {@code Mob} 판별,
 * {@code DamageSource.getEntity()} 로 가해자를 찾는 것, {@code TeamManager} 조회)은 살아 있는
 * 서버와 {@code ServerPlayer} 가 있어야 해서 이 저장소의 시험 환경에서는 지날 수 없다. 그
 * 기준은 {@code PerkKillRewards.reward} 의 것과 <b>한 줄씩 같은 모양</b>으로 적어 두었고,
 * {@code OnKillEffectTest} 도 같은 이유로 그 부분을 다루지 않는다.
 *
 * <p>대신 그 코드가 실제로 부르는 판단과 계산
 * ({@link SpreadDamageManager#clearsOnKill}, {@link SpreadDamageManager#clearPending})은 월드를
 * 보지 않게 떼어 두었으므로 전부 여기서 본다.
 */
class SpreadDamageKillClearTest {

	private static final float EPSILON = 0.0001F;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		SpreadDamageManager.resetForTesting();
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 남은 몫 지우기

	@Test
	void 분산_중에_적을_잡으면_남은_몫이_사라진다() {
		UUID teamId = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(teamId, 12.0F, 4);
		assertTrue(SpreadDamageManager.isSpreading(teamId), "심은 큐가 나뉘는 중이어야 한다");

		float cleared = SpreadDamageManager.clearPending(teamId);

		assertEquals(12.0F, cleared, EPSILON, "남아 있던 몫 전부가 지워진다");
		assertEquals(0.0F, SpreadDamageManager.remaining(teamId), EPSILON);
		assertFalse(SpreadDamageManager.isSpreading(teamId));
	}

	/**
	 * 회복 금지는 {@code SpreadDamageManager.blocksHealing} 이 {@link SpreadDamageManager#isSpreading}
	 * 에게 묻는다. 큐를 닫으면 그 물음이 거짓이 되므로 따로 걷어낼 상태가 없다.
	 *
	 * <p>{@code blocksHealing} 자체는 {@code ServerPlayer} 를 요구해 여기서 부를 수 없다. 그 함수가
	 * 다른 판단을 하지 않고 이 값만 돌려준다는 것은 코드에서 확인했다.
	 */
	@Test
	void 남은_몫을_지우면_회복_금지도_함께_풀린다() {
		UUID teamId = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(teamId, 8.0F, 8);

		SpreadDamageManager.clearPending(teamId);

		assertFalse(SpreadDamageManager.isSpreading(teamId),
				"회복 금지가 큐보다 오래 남으면 대가가 약속보다 길어진다");
	}

	@Test
	void 이미_들어간_피해는_되돌아오지_않는다() {
		UUID teamId = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(teamId, 12.0F, 4);

		// 1초 — 12 을 넷으로 나눈 3 이 이미 들어갔다.
		float delivered = SpreadDamageManager.takeSliceForTesting(teamId);
		assertEquals(3.0F, delivered, EPSILON);
		assertEquals(9.0F, SpreadDamageManager.remaining(teamId), EPSILON);

		float cleared = SpreadDamageManager.clearPending(teamId);

		assertEquals(9.0F, cleared, EPSILON, "지워지는 것은 남은 몫뿐이다");
		assertEquals(12.0F, delivered + cleared, EPSILON,
				"들어간 3 과 지워진 9 를 합하면 원래 맞은 12 다 — 없던 체력이 생기지 않았다");
	}

	/**
	 * 지울 때 {@code forget} 을 쓰면 안 된다. {@code forget} 은 흉내 낸 무적시간(Guard)까지 버려서,
	 * 처치 직후 20틱 안에 다시 맞은 피해가 바닐라라면 막혔을 텐데도 통째로 큐에 쌓인다.
	 */
	@Test
	void 지워도_표는_남는다_무적시간_흉내를_잃지_않게() {
		UUID teamId = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(teamId, 5.0F, 5);

		SpreadDamageManager.clearPending(teamId);
		assertTrue(SpreadDamageManager.trackedForTesting(teamId),
				"남은 몫만 지운다. 표는 무적시간이 다 흐른 뒤 틱이 치운다");

		SpreadDamageManager.forget(teamId);
		assertFalse(SpreadDamageManager.trackedForTesting(teamId), "전멸·해체는 표째로 버린다");
	}

	@Test
	void 지울_것이_없으면_아무_일도_없다() {
		assertEquals(0.0F, SpreadDamageManager.clearPending(UUID.randomUUID()), EPSILON);
		assertEquals(0.0F, SpreadDamageManager.clearPending(null), EPSILON);

		// 몫이 다 들어가 닫힌 큐를 다시 지워도 0 이다.
		UUID teamId = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(teamId, 4.0F, 1);
		SpreadDamageManager.takeSliceForTesting(teamId);

		assertEquals(0.0F, SpreadDamageManager.clearPending(teamId), EPSILON);
	}

	// ------------------------------------------------------------------ 누구의 처치가 세어지는가

	@Test
	void 완충을_가진_팀만_지울_수_있다(@TempDir Path dir) throws IOException {
		load(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:cushion", "rarity": "prism", "name": "완충",
				      "effects": [ { "type": "spread_damage", "seconds": 8 } ] },
				    { "id": "sharedfate:그밖에", "rarity": "gold", "name": "그밖에",
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.2 } ] }
				  ]
				}
				""");

		assertTrue(SpreadDamageManager.clearsOnKill(state("sharedfate:cushion")));
		assertFalse(SpreadDamageManager.clearsOnKill(state("sharedfate:그밖에")),
				"완충이 없는 팀은 몹을 잡아도 얻는 것이 없다");
	}

	@Test
	void 증강이_없거나_꺼진_팀은_지울_수_없다(@TempDir Path dir) throws IOException {
		load(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:cushion", "rarity": "prism", "name": "완충",
				      "effects": [ { "type": "spread_damage", "seconds": 8 } ] }
				  ]
				}
				""");

		assertFalse(SpreadDamageManager.clearsOnKill(null));
		assertFalse(SpreadDamageManager.clearsOnKill(TeamState.fresh(20.0F)), "가진 증강이 없다");

		TeamState 꺼짐 = state("sharedfate:cushion");
		꺼짐.perksEnabled = false;
		assertFalse(SpreadDamageManager.clearsOnKill(꺼짐), "증강이 꺼진 판에서는 아무 효과도 없다");
	}

	@Test
	void 풀에서_사라진_증강_id_는_건너뛴다(@TempDir Path dir) throws IOException {
		load(dir, "{ \"perks\": [] }");

		assertFalse(SpreadDamageManager.clearsOnKill(state("sharedfate:사라진것")));
	}

	// ------------------------------------------------------------------ 포격 무시 (2026-10-04)

	/**
	 * <b>포격 중에는 「완충」의 흉내 쿨타임(Guard)이 막지 않는다.</b>
	 *
	 * <p>사람이 2026-10-04 에 「포격 무시」를 골랐다 — 완충의 흉내 쿨타임도 같은 규칙을 따라야
	 * 한다. 앞 원을 맞은 지 8틱이면 Guard 는 쿨타임 12, 직전 피해량은 같은 포격 값이다. 무시하지
	 * 않으면 둘째 원이 통째로 버려지고(피해 0 짜리 피격 연출만 남는다), 무시하면 온전히 미뤄진다.
	 *
	 * <p>{@code capture} 자체는 살아 있는 팀·플레이어가 있어야 해서 여기서 부르지 않는다. 대신
	 * 그 메서드가 {@code gate} 를 부르기 <b>전에</b> {@code DragonFireBarrage.ignoresCooldown} 을
	 * 묻는다는 것을 바이트코드로 붙든다. 그 물음이 빠지면 이 시험이 깨진다.
	 */
	@Test
	void 포격_중에는_Guard_가_막지_않는다() throws IOException {
		float hard = DragonFireBarrage.DAMAGE_PER_BLAST * 1.5F;
		int guardTicks = DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS
				- DragonFireBarrage.BLAST_INTERVAL_TICKS;
		assertTrue(guardTicks > SpreadDamageManager.INVULNERABLE_GATE_TICKS,
				"전제가 틀렸다 — 둘째 원이 Guard 의 쿨타임 안에 오지 않는다");

		assertEquals(0.0F, SpreadDamageManager.gate(hard, hard, guardTicks, false).accepted(), EPSILON,
				"무시하지 않으면 둘째 원이 버려진다 — 고치기 전의 증상");
		assertEquals(hard, SpreadDamageManager.gate(hard, hard, guardTicks, true).accepted(), EPSILON,
				"포격 무시면 둘째 원이 온전히 미뤄져야 한다");

		// capture 가 gate 앞에서 포격 표시를 묻는지 — 호출 순서를 바이트코드에서 뽑는다.
		List<String> calls = new ArrayList<>();
		try (InputStream in = SpreadDamageManager.class
				.getResourceAsStream("/com/sharedfate/sync/SpreadDamageManager.class")) {
			assertNotNull(in, "SpreadDamageManager.class 를 찾지 못했다");
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					if (!name.equals("capture")) {
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String callName,
								String callDescriptor, boolean isInterface) {
							if (owner.equals("com/sharedfate/sync/DragonFireBarrage")
									&& callName.equals("ignoresCooldown")) {
								calls.add("ignoresCooldown");
							} else if (owner.equals("com/sharedfate/sync/SpreadDamageManager")
									&& callName.equals("gate")) {
								calls.add("gate");
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}
		assertEquals(List.of("ignoresCooldown", "gate"), calls,
				"capture 가 gate 앞에서 DragonFireBarrage.ignoresCooldown 을 묻지 않는다 — "
						+ "「완충」을 가진 팀에서만 둘째 원이 Guard 에 먹힌다");

		assertFalse(DragonFireBarrage.ignoresCooldown(),
				"포격을 넣는 중이 아닌데 표시가 켜져 있다 — 다른 피해원까지 Guard 를 뚫는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static TeamState state(String... ownedPerks) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		for (String perkId : ownedPerks) {
			state.ownedPerks.add(perkId);
		}
		return state;
	}

	private static void load(Path dir, String json) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), json, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
	}
}
