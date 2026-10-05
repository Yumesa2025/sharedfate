package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.sync.TrialWarning;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * S2C — <b>드래곤 패턴 타이머 HUD.</b> 「몇 초 뒤에 무엇이 오는가」.
 *
 * <h2>사람이 정한 것 (2026-10-05)</h2>
 *
 * <p>사람 말: <b>「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게 오른쪽
 * 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」</b>. 예시 화면을 보고 「이대로
 * 진행」했다. 화면은 둘이다.
 *
 * <ul>
 *   <li><b>오른쪽 위 패널 「다가오는 패턴」</b>({@link #timers}) — 한 줄 = 색 점 + 이름 + 남은 초 +
 *       남은 비율 바. 남은 시간이 짧은 줄이 위</li>
 *   <li><b>가운데 위 시전 바</b>({@link #cast}) — 최후의 저항에서만. 쉬는 중 / 예고 / 진행 + 한 줄
 *       대처법</li>
 * </ul>
 *
 * <h2>서버가 정확히 아는 값만 싣는다</h2>
 *
 * <p>남은 틱은 <b>실행기의 상태에서 읽은 것</b>이다({@code com.sharedfate.sync.TrialTimers}).
 * 클라이언트가 주기를 다시 세면 시련 룰렛의 멈춤 · 서버 재시작 · 건너뛴 주기에서 어긋나고, 이
 * 화면이 거짓말을 하면 그 자리에서 사람이 죽는다 — 전멸은 월드 삭제다.
 *
 * <h2>클라이언트가 틱마다 스스로 줄인다</h2>
 *
 * <p>서버는 상태가 바뀔 때와 그 외 20틱마다 한 번만 보낸다. 그 사이는 받는 쪽이 클라이언트 틱마다
 * {@link Entry#remainingTicks}·{@link Cast#remainingTicks} 를 1 씩 줄여 그린다 — <b>단
 * {@link #frozen} 이 참이면 줄이지 않는다.</b> 시련 룰렛·증강 선택이 판을 얼리면 서버의 게임 시각이
 * 멈추고(TrialFreeze), 실행기의 시계도 함께 선다.
 *
 * <h2>「그만 그려라」를 보낸다 — 한 번</h2>
 *
 * <p>{@code TrialHotbarLockPayload} 는 끊기면 받는 쪽이 스스로 지운다. 이 묶음은 반대로
 * <b>{@link #visible} 거짓을 한 번</b> 보낸다. 끊기는 것만으로 지우면 얼어 있는 17초 동안(서버는
 * 그 사이에도 20틱마다 보낸다 — 아래 「빈도」) 말고도, 서버가 바쁜 순간에 HUD 가 깜빡인다. 엔드를
 * 떠난 사람 · 시련을 끈 팀 · 전투가 끝난 팀에게 서버가 한 번만 보낸다. 받는 쪽은 접속이 끊길 때도
 * 스스로 지워야 한다(그 길로는 서버가 아무것도 못 보낸다).
 *
 * @param visible 거짓이면 HUD 를 지운다. 이때 나머지 칸은 비어 있다
 * @param frozen  판이 얼어 있는가. 참이면 받는 쪽이 카운트다운을 멈춘다
 * @param timers  오른쪽 위 패널의 줄들. 서버가 남은 시간이 짧은 순으로 담아 보내지만 받는 쪽도 줄여
 *                가며 다시 정렬해야 한다
 * @param cast    가운데 위 시전 바. 최후의 저항에서만 있다
 */
public record TrialTimersPayload(boolean visible, boolean frozen, List<Entry> timers,
		Optional<Cast> cast) implements CustomPacketPayload {

	/**
	 * 한 번에 싣는 줄의 상한. 지금 한 판에 나올 수 있는 줄은 기본 패시브 1 + 시간이 정해진 카드
	 * 11 = 12 가 최대다. 넘치면 남은 시간이 긴 쪽부터 잘린다 — 화면이 넘치는 것보다 낫다.
	 */
	public static final int MAX_TIMERS = 16;
	/** 줄 열쇠의 글자 상한. 카드 id 에 순번이 붙은 것이 가장 길다. */
	public static final int MAX_ID_LENGTH = 64;
	/** 이름 · 시전 바 제목의 글자 상한. */
	public static final int MAX_LABEL_LENGTH = 48;
	/** 체력형 줄의 글자(「체력 25%」 · 「진행 중 · 3개」)의 상한. */
	public static final int MAX_VALUE_LENGTH = 32;
	/** 대처법 한 줄의 글자 상한. */
	public static final int MAX_HINT_LENGTH = 96;

	/** HUD 를 지우라는 묶음. 칸이 전부 비어 있다. */
	public static final TrialTimersPayload HIDDEN =
			new TrialTimersPayload(false, false, List.of(), Optional.empty());

	/**
	 * 줄의 <b>종류</b> — 곧 색. 사람이 승인한 화면의 여섯 색이다.
	 *
	 * <p>바이트로 싣고 색은 여기 한 곳에 둔다. 클라이언트가 색을 따로 적으면 서버의 바닥 표식
	 * 색({@code TrialWarning.Colors})과 HUD 색이 언젠가 어긋난다 — 밀침의 파랑은 그쪽 값을 그대로
	 * 가리킨다(사람 화면: 「밀침 파랑 #4AA3FF(TrialWarning.Colors.SHOVE 와 같게)」).
	 *
	 * <p>⚠ <b>{@link #NEUTRAL} 은 사람이 승인한 여섯 밖이다.</b> 시전 바가 「쉬는 중 · 다음 패턴
	 * 무작위」와 「진입 보호막」일 때 칠할 종류가 여섯 어디에도 없어서 더했다 — 패턴이 아직 정해지지
	 * 않았는데 빨강이나 파랑을 칠하면 「무엇이 오는가」를 색이 거짓으로 말한다.
	 */
	public static final class Kind {
		/** 피해 — 빨강. */
		public static final byte DAMAGE = 0;
		/** 밀침 — 파랑. */
		public static final byte SHOVE = 1;
		/** 번개 — 노랑. */
		public static final byte LIGHTNING = 2;
		/** 방해 · 당김 — 보라. */
		public static final byte DISRUPT = 3;
		/** 안전지대 — 주황. */
		public static final byte ZONE = 4;
		/** 드래곤 회복 — 초록. */
		public static final byte HEAL = 5;
		/** 아직 정해지지 않음(쉬는 중 · 보호막) — 회색. 승인된 여섯 밖이다(위 설명). */
		public static final byte NEUTRAL = 6;

		public static final int COLOR_DAMAGE = 0xEF5350;
		public static final int COLOR_SHOVE = TrialWarning.Colors.SHOVE;
		public static final int COLOR_LIGHTNING = 0xF2C94C;
		public static final int COLOR_DISRUPT = 0xB07CFF;
		public static final int COLOR_ZONE = 0xFF8A3D;
		public static final int COLOR_HEAL = 0x3DDC97;
		public static final int COLOR_NEUTRAL = 0xB0B0B0;

		private Kind() {
		}

		/** 종류의 색(RGB, 알파 없음). 모르는 값은 회색이다 — 새 서버가 보낸 새 종류에 터지지 않는다. */
		public static int color(byte kind) {
			return switch (kind) {
				case DAMAGE -> COLOR_DAMAGE;
				case SHOVE -> COLOR_SHOVE;
				case LIGHTNING -> COLOR_LIGHTNING;
				case DISRUPT -> COLOR_DISRUPT;
				case ZONE -> COLOR_ZONE;
				case HEAL -> COLOR_HEAL;
				default -> COLOR_NEUTRAL;
			};
		}
	}

	/**
	 * 오른쪽 위 패널의 한 줄.
	 *
	 * <h2>모양이 셋이다 — {@link #mode}</h2>
	 *
	 * <ul>
	 *   <li>{@link #MODE_COUNTDOWN} — <b>다가오는 사건.</b> {@link #remainingTicks} 가 실제 피해 ·
	 *       효과까지 남은 틱이다. {@link #warnTicks} 이하로 내려오면 바닥 표식이 이미 깔린
	 *       「예고 중」, 100틱(5초) 이하면 「임박」, 그 위는 「대기」다</li>
	 *   <li>{@link #MODE_ACTIVE} — <b>지금 벌어지는 중.</b> {@link #remainingTicks} 가 <b>끝날 때까지</b>
	 *       남은 틱이다(밤의 군세 20초 · 소용돌이가 중앙에 닿을 때까지 · 안전지대가 줄어드는 5초).
	 *       「이름 · 진행 중」으로 그린다. ⚠ 제안 형태에 없던 모양이다 — 이것이 없으면 「구체가 날아오는
	 *       중」에 「과충전까지 45초」를 띄우게 된다</li>
	 *   <li>{@link #MODE_HEALTH} — <b>체력으로 정해지는 것.</b> 초 대신 {@link #valueText}
	 *       (「체력 25%」)를 그리고 바는 {@link #fill}(다음 문턱까지의 거리)이다. 남은 틱 칸은 0 이고
	 *       줄이지 않는다</li>
	 * </ul>
	 *
	 * @param id             줄 열쇠. 같은 줄은 묶음이 바뀌어도 같은 값이다(카드 id · 「passive:barrage」 등)
	 * @param label          이름(「자리 폭격」 · 「안전지대 → 32칸」)
	 * @param kind           종류 — 곧 색({@link Kind})
	 * @param mode           모양({@link #MODE_COUNTDOWN} · {@link #MODE_ACTIVE} · {@link #MODE_HEALTH})
	 * @param remainingTicks 사건까지(또는 끝날 때까지) 남은 틱. 체력형이면 0
	 * @param totalTicks     그 카운트다운의 전체 길이 — 바의 분모. 언제나 {@code remainingTicks} 이상
	 * @param warnTicks      사건 전에 바닥 표식이 깔리는 길이. 0 이면 바닥 예고가 없는 것이다
	 * @param valueText      체력형의 글자. 시간형이면 빈 글자
	 * @param fill           체력형의 바(0~1). 시간형이면 -1
	 */
	public record Entry(String id, String label, byte kind, byte mode, int remainingTicks,
			int totalTicks, int warnTicks, String valueText, float fill) {

		/** 다가오는 사건. */
		public static final byte MODE_COUNTDOWN = 0;
		/** 지금 벌어지는 중 — 남은 틱은 끝날 때까지다. */
		public static final byte MODE_ACTIVE = 1;
		/** 체력으로 정해지는 것 — 글자와 바만 쓴다. */
		public static final byte MODE_HEALTH = 2;

		public Entry {
			// 밖에서 오는 값이다(받는 쪽에서도 같은 생성자가 돈다). 글자가 null 이거나 길면 인코딩이
			// 통째로 터지고, VAR_INT 에 음수를 실으면 다섯 바이트를 쓴다.
			id = clip(id, MAX_ID_LENGTH);
			label = clip(label, MAX_LABEL_LENGTH);
			valueText = clip(valueText, MAX_VALUE_LENGTH);
			remainingTicks = Math.max(0, remainingTicks);
			totalTicks = Math.max(remainingTicks, Math.max(0, totalTicks));
			warnTicks = Math.max(0, warnTicks);
			if (mode == MODE_HEALTH) {
				fill = Float.isNaN(fill) ? 0.0F : Math.max(0.0F, Math.min(1.0F, fill));
			} else {
				fill = -1.0F;
			}
		}

		/** 시간형(다가오는 사건) 한 줄. */
		public static Entry countdown(String id, String label, byte kind, int remainingTicks,
				int totalTicks, int warnTicks) {
			return new Entry(id, label, kind, MODE_COUNTDOWN, remainingTicks, totalTicks, warnTicks,
					"", -1.0F);
		}

		/** 지금 벌어지는 중인 한 줄. 남은 틱은 끝날 때까지다. */
		public static Entry active(String id, String label, byte kind, int remainingTicks,
				int totalTicks) {
			return new Entry(id, label, kind, MODE_ACTIVE, remainingTicks, totalTicks, 0, "", -1.0F);
		}

		/** 체력형 한 줄. */
		public static Entry health(String id, String label, byte kind, String valueText, float fill) {
			return new Entry(id, label, kind, MODE_HEALTH, 0, 0, 0, valueText, fill);
		}

		public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(MAX_ID_LENGTH), Entry::id,
				ByteBufCodecs.stringUtf8(MAX_LABEL_LENGTH), Entry::label,
				ByteBufCodecs.BYTE, Entry::kind,
				ByteBufCodecs.BYTE, Entry::mode,
				ByteBufCodecs.VAR_INT, Entry::remainingTicks,
				ByteBufCodecs.VAR_INT, Entry::totalTicks,
				ByteBufCodecs.VAR_INT, Entry::warnTicks,
				ByteBufCodecs.stringUtf8(MAX_VALUE_LENGTH), Entry::valueText,
				ByteBufCodecs.FLOAT, Entry::fill,
				Entry::new);
	}

	/**
	 * 가운데 위 시전 바(최후의 저항).
	 *
	 * <h2>상태 셋 — 사람이 승인한 화면 그대로</h2>
	 *
	 * <ul>
	 *   <li>{@link #STATE_IDLE} — 쉬는 중. 제목은 「다음 패턴 · 무작위」(패턴은 고르는 순간 정해져
	 *       미리 알 수 없다) 또는 진입 보호막 구간의 「최후의 저항 · 보호막」. 남은 틱은 <b>다음 패턴을
	 *       고를 때까지</b>다</li>
	 *   <li>{@link #STATE_WARNING} — 예고 중. 제목은 패턴 이름. 남은 틱은 <b>발동까지</b>이고 바는
	 *       차오른다(「N초 뒤 발동」)</li>
	 *   <li>{@link #STATE_RUNNING} — 진행 중. 「이름 · 진행 중」. 남은 틱은 <b>끝날 때까지</b>다</li>
	 * </ul>
	 *
	 * @param title          제목
	 * @param kind           종류 — 곧 색({@link Kind}). 쉬는 중이면 {@link Kind#NEUTRAL}
	 * @param state          상태({@link #STATE_IDLE} · {@link #STATE_WARNING} · {@link #STATE_RUNNING})
	 * @param remainingTicks 그 상태가 끝날 때까지 남은 틱
	 * @param totalTicks     그 상태의 전체 길이 — 바의 분모. 언제나 {@code remainingTicks} 이상
	 * @param hint           시전 바 아래 대처법 한 줄. 없으면 빈 글자
	 */
	public record Cast(String title, byte kind, byte state, int remainingTicks, int totalTicks,
			String hint) {

		/** 쉬는 중 — 다음 패턴은 아직 모른다. */
		public static final byte STATE_IDLE = 0;
		/** 예고 중 — 남은 틱은 발동까지. */
		public static final byte STATE_WARNING = 1;
		/** 진행 중 — 남은 틱은 끝날 때까지. */
		public static final byte STATE_RUNNING = 2;

		public Cast {
			title = clip(title, MAX_LABEL_LENGTH);
			hint = clip(hint, MAX_HINT_LENGTH);
			remainingTicks = Math.max(0, remainingTicks);
			totalTicks = Math.max(remainingTicks, Math.max(0, totalTicks));
		}

		public static final StreamCodec<ByteBuf, Cast> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(MAX_LABEL_LENGTH), Cast::title,
				ByteBufCodecs.BYTE, Cast::kind,
				ByteBufCodecs.BYTE, Cast::state,
				ByteBufCodecs.VAR_INT, Cast::remainingTicks,
				ByteBufCodecs.VAR_INT, Cast::totalTicks,
				ByteBufCodecs.stringUtf8(MAX_HINT_LENGTH), Cast::hint,
				Cast::new);
	}

	public static final Type<TrialTimersPayload> TYPE =
			new Type<>(SharedFateMod.id("trial_timers"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialTimersPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.BOOL, TrialTimersPayload::visible,
					ByteBufCodecs.BOOL, TrialTimersPayload::frozen,
					Entry.CODEC.apply(ByteBufCodecs.list(MAX_TIMERS)), TrialTimersPayload::timers,
					ByteBufCodecs.optional(Cast.CODEC), TrialTimersPayload::cast,
					TrialTimersPayload::new);

	public TrialTimersPayload {
		timers = timers == null ? List.of() : timers;
		if (timers.size() > MAX_TIMERS) {
			// 코덱 상한을 넘으면 인코딩이 터진다. 서버가 짧은 순으로 담으므로 뒤(긴 쪽)를 자른다.
			timers = timers.subList(0, MAX_TIMERS);
		}
		timers = List.copyOf(timers);
		cast = cast == null ? Optional.empty() : cast;
		if (!visible) {
			// 지우라는 묶음에 내용을 싣지 않는다. 받는 쪽이 「지워라」와 「빈 HUD」를 헷갈리지 않게.
			frozen = false;
			timers = List.of();
			cast = Optional.empty();
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static String clip(@Nullable String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, max);
	}
}
