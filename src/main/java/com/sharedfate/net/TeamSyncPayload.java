package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.sharedfate.inventory.ExpandedInventoryManager;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * S2C — 팀 명단과 공유 레벨 동기화.
 *
 * <p>레벨 구간 계산은 전부 서버가 한다. 클라이언트는 받은 값을 그대로 보여주기만 하므로
 * 증강 구간 값이 바뀌어도 클라이언트를 고칠 필요가 없다.
 *
 * @param members       팀원 목록. 팀에 속하지 않으면 빈 목록
 * @param teamName      팀 이름. 팀에 속하지 않으면 빈 문자열
 * @param xpLevel       팀이 공유하는 경험치 레벨
 * @param nextPerkLevel 다음 증강이 나오는 레벨. 남은 증강이 없거나 증강을 쓰지 않으면 0
 * @param maxHealth     팀이 정한 공유 최대 체력
 * @param swapIntervalMinutes 위치 교환 주기(분). 꺼져 있으면 0
 * @param options       팀을 만들 때 정한 켜고 끄기 셋
 * @param leaderId      팀 리더의 UUID. 팀이 없으면 0 UUID.
 *                      묶음을 팀 전체에 한 번만 만들어 보내므로, 받는 쪽이 자기 UUID 와
 *                      견주어 리더인지 판단한다
 */
public record TeamSyncPayload(List<Member> members, String teamName, int xpLevel, int nextPerkLevel,
		float maxHealth, int swapIntervalMinutes, Options options, UUID leaderId)
		implements CustomPacketPayload {
	public record Member(UUID id, String name, int selectedSlot) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Member> CODEC =
				StreamCodec.composite(
						UUIDUtil.STREAM_CODEC, Member::id,
						ByteBufCodecs.STRING_UTF8, Member::name,
						ByteBufCodecs.VAR_INT, Member::selectedSlot,
						Member::new);
	}

	/**
	 * 켜고 끄기를 한 칸에 담는 묶음.
	 *
	 * <p>바깥 {@link #CODEC} 의 {@code StreamCodec.composite} 는 항목 <b>8개가 상한</b>이고 이미
	 * 다 찼다. 앞으로 켜고 끄기가 더 늘어도 이 안에 넣으면 바깥은 그대로다.
	 *
	 * <p>{@code runStarted} 와 {@code unlockedExtraSlots} 만은 성격이 다르다. 나머지는 팀을 만들
	 * 때 정해 그 뒤로 바뀌지 않는 값이지만 이 둘은 <b>회차마다 바뀌는 진행 상황</b>이다.
	 *
	 * <p>이 묶음 자체도 {@code StreamCodec.composite} 라 <b>8개가 상한</b>이다. 지금 6개를
	 * 썼으니 남은 자리는 둘이고, 그다음에는 여기도 한 번 더 접어야 한다.
	 *
	 * @param perks       이 팀이 증강을 쓰는가
	 * @param damageAlert 피격 알림을 보여 주는가
	 * @param deathAlert  사망 알림을 보여 주는가
	 * @param runStarted  회차가 시작되었는가. 거짓이면 「시작 대기」이고, 팀 화면이 리더에게
	 *                    「게임 시작」 단추를 그린다
	 * @param dragonTrials 이 팀이 엔더 드래곤 시련을 쓰는가. <b>클라이언트는 이 값으로 아무
	 *                    판단도 하지 않는다</b> — 시련을 걸고 안 걸고는 전부 서버가 한다.
	 *                    설정 탭에 한 줄을 적기 위한 값이고, 그 한 줄이 기본값 끔 때문에 생기는
	 *                    「왜 시련이 안 뜨지」에 게임 안에서 답하는 가장 싼 길이다
	 */
	public record Options(boolean perks, boolean damageAlert, boolean deathAlert,
			boolean runStarted, int unlockedExtraSlots, boolean dragonTrials) {
		/**
		 * 팀에 속하지 않았을 때의 값.
		 *
		 * <p>추가 칸은 기본 개수로 둔다. 팀이 없으면 어차피 확장 인벤토리가 통째로 접힌다.
		 */
		public static final Options NONE = new Options(false, false, false, false,
				ExpandedInventoryManager.BASE_EXTRA_SIZE, false);

		public static final StreamCodec<RegistryFriendlyByteBuf, Options> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.BOOL, Options::perks,
						ByteBufCodecs.BOOL, Options::damageAlert,
						ByteBufCodecs.BOOL, Options::deathAlert,
						ByteBufCodecs.BOOL, Options::runStarted,
						ByteBufCodecs.VAR_INT, Options::unlockedExtraSlots,
						ByteBufCodecs.BOOL, Options::dragonTrials,
						Options::new);

		public Options {
			// 클라이언트는 이 값으로 화면을 그린다. 범위를 벗어난 값이 오면 창이 깨지므로
			// 받는 쪽에서도 접는다.
			unlockedExtraSlots = Math.max(0,
					Math.min(ExpandedInventoryManager.EXTRA_SIZE, unlockedExtraSlots));
		}

		/**
		 * 추가 칸 수를 적지 않으면 기본 개수로 본다.
		 *
		 * <p>이 값이 생기기 전의 호출을 위한 자리다. 표준 생성자에 인자를 끼워 넣으면 그 자리를
		 * 쓰던 곳이 전부 깨진다.
		 */
		public Options(boolean perks, boolean damageAlert, boolean deathAlert, boolean runStarted) {
			this(perks, damageAlert, deathAlert, runStarted,
					ExpandedInventoryManager.BASE_EXTRA_SIZE, false);
		}

		/**
		 * 드래곤 시련을 적지 않으면 <b>끔</b>으로 본다. 기본값과 같은 값이라야 한다.
		 *
		 * <p>{@link ExpandedInventoryManager#BASE_EXTRA_SIZE} 짜리 생성자와 같은 이유의
		 * 자리다 — 표준 생성자에 인자를 끼워 넣으면 그 자리를 쓰던 곳이 전부 깨진다.
		 */
		public Options(boolean perks, boolean damageAlert, boolean deathAlert, boolean runStarted,
				int unlockedExtraSlots) {
			this(perks, damageAlert, deathAlert, runStarted, unlockedExtraSlots, false);
		}
	}

	/** 팀에 속하지 않은 상태. */
	public static final TeamSyncPayload EMPTY =
			new TeamSyncPayload(List.of(), "", 0, 0, 20.0F, 0, Options.NONE, new UUID(0L, 0L));

	public static final Type<TeamSyncPayload> TYPE = new Type<>(SharedFateMod.id("team_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TeamSyncPayload> CODEC =
			StreamCodec.composite(
					Member.CODEC.apply(ByteBufCodecs.list(16)), TeamSyncPayload::members,
					ByteBufCodecs.STRING_UTF8, TeamSyncPayload::teamName,
					ByteBufCodecs.VAR_INT, TeamSyncPayload::xpLevel,
					ByteBufCodecs.VAR_INT, TeamSyncPayload::nextPerkLevel,
					ByteBufCodecs.FLOAT, TeamSyncPayload::maxHealth,
					ByteBufCodecs.VAR_INT, TeamSyncPayload::swapIntervalMinutes,
					Options.CODEC, TeamSyncPayload::options,
					UUIDUtil.STREAM_CODEC, TeamSyncPayload::leaderId,
					TeamSyncPayload::new);

	public TeamSyncPayload {
		members = List.copyOf(members);
		// VAR_INT 는 음수를 담기에 낭비가 크므로 세 값 모두 0 이상으로 맞춘다.
		xpLevel = Math.max(0, xpLevel);
		nextPerkLevel = Math.max(0, nextPerkLevel);
		swapIntervalMinutes = Math.max(0, swapIntervalMinutes);
	}

	/** 이 팀이 증강을 쓰는가. */
	/** 이 팀에 열려 있는 추가 인벤토리 칸 수. */
	public int unlockedExtraSlots() {
		return options == null
				? ExpandedInventoryManager.BASE_EXTRA_SIZE : options.unlockedExtraSlots();
	}

	public boolean perksEnabled() {
		return options.perks();
	}

	/** 피격 알림을 보여 주는 팀인가. */
	public boolean damageAlertEnabled() {
		return options.damageAlert();
	}

	/** 사망 알림을 보여 주는 팀인가. */
	public boolean deathAlertEnabled() {
		return options.deathAlert();
	}

	/** 이 팀이 엔더 드래곤 시련을 쓰는가. 거짓이면 바닐라 드래곤전이다. */
	public boolean dragonTrialsEnabled() {
		return options.dragonTrials();
	}

	/** 이 팀의 회차가 시작되었는가. 거짓이면 「시작 대기」다. */
	public boolean runStarted() {
		return options.runStarted();
	}

	/** 이 사람이 팀 리더인가. */
	public boolean isLeader(UUID player) {
		return leaderId.equals(player);
	}

	/** 위치 교환이 켜져 있는가. */
	public boolean swapEnabled() {
		return swapIntervalMinutes > 0;
	}

	/** 다음 증강까지 남은 레벨. 더 이상 받을 증강이 없으면 -1. */
	public int levelsToNextPerk() {
		return nextPerkLevel <= 0 ? -1 : Math.max(0, nextPerkLevel - xpLevel);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
