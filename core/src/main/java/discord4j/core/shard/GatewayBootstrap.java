/*
 * This file is part of Discord4J.
 *
 * Discord4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Discord4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J. If not, see <http://www.gnu.org/licenses/>.
 */

package discord4j.core.shard;

import discord4j.common.LogUtil;
import discord4j.common.ReactorResources;
import discord4j.common.annotations.Experimental;
import discord4j.common.retry.ReconnectOptions;
import discord4j.core.CoreResources;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.GatewayResources;
import discord4j.core.event.EventDispatcher;
import discord4j.core.event.dispatch.DispatchContext;
import discord4j.core.event.dispatch.DispatchEventMapper;
import discord4j.core.event.domain.Event;
import discord4j.core.object.presence.Presence;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.core.state.StateHolder;
import discord4j.core.state.StateView;
import discord4j.discordjson.json.ActivityUpdateRequest;
import discord4j.discordjson.json.MessageData;
import discord4j.discordjson.json.gateway.Dispatch;
import discord4j.discordjson.json.gateway.StatusUpdate;
import discord4j.discordjson.possible.Possible;
import discord4j.gateway.*;
import discord4j.gateway.intent.IntentSet;
import discord4j.gateway.json.ShardAwareDispatch;
import discord4j.gateway.limiter.PayloadTransformer;
import discord4j.gateway.limiter.RateLimitTransformer;
import discord4j.gateway.payload.JacksonPayloadReader;
import discord4j.gateway.payload.JacksonPayloadWriter;
import discord4j.gateway.payload.PayloadReader;
import discord4j.gateway.payload.PayloadWriter;
import discord4j.gateway.retry.GatewayStateChange;
import discord4j.rest.util.RouteUtils;
import discord4j.store.api.Store;
import discord4j.store.api.primitive.ForwardingStoreService;
import discord4j.store.api.service.StoreService;
import discord4j.store.api.service.StoreServiceLoader;
import discord4j.store.api.util.StoreContext;
import discord4j.store.jdk.JdkStoreService;
import discord4j.voice.DefaultVoiceConnectionFactory;
import discord4j.voice.VoiceConnection;
import discord4j.voice.VoiceConnectionFactory;
import discord4j.voice.VoiceReactorResources;
import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static discord4j.common.LogUtil.format;

/**
 * Builder to create a shard group connecting to Discord Gateway to produce a {@link GatewayDiscordClient}. A shard
 * group represents a set of shards for a given bot that will share some key resources like entity caching and event
 * dispatching. Defaults to creating an automatic sharding group using all shards up to the recommended amount. Refer
 * to each setter for more details about the default values for each configuration. Some of the commonly used ones are:
 * <ul>
 *     <li>Setting the number of shards to connect through the
 *     {@link #setSharding(ShardingStrategy)} method.</li>
 *     <li>Setting the initial status of the bot depending on the shard, through
 *     {@link #setInitialStatus(Function)}</li>
 *     <li>Customize the entity cache using {@link #setStoreService(StoreService)}</li>
 * </ul>
 * <p>
 * One of the following methods must be subscribed to in order to begin establishing Discord Gateway connections:
 * <ul>
 *     <li>{@link #login()} to obtain a {@link Mono} for a {@link GatewayDiscordClient} that can be externally
 *     managed.</li>
 *     <li>{@link #login(Function)} to customize the {@link GatewayClient} instances to build.</li>
 *     <li>{@link #withGateway(Function)} to work with the {@link GatewayDiscordClient} in a scoped way, providing
 *     a mapping function that will close and release all resources on disconnection.</li>
 * </ul>
 * This bootstrap emits a result depending on the configuration of {@link #setAwaitConnections(boolean)}.
 *
 * @param <O> the configuration flavor supplied to the {@link GatewayClient} instances to be built.
 */
public class GatewayBootstrap<O extends GatewayOptions> {

    private static final Logger log = Loggers.getLogger(GatewayBootstrap.class);

    private final DiscordClient client;
    private final Function<GatewayOptions, O> optionsModifier;

    private ShardingStrategy shardingStrategy = ShardingStrategy.recommended();
    private Boolean awaitConnections = null;
    private ShardCoordinator shardCoordinator = null;
    private EventDispatcher eventDispatcher = null;
    private StoreService storeService = null;
    private InvalidationStrategy invalidationStrategy = null;
    private MemberRequestFilter memberRequestFilter = MemberRequestFilter.withLargeGuilds();
    private Function<ShardInfo, StatusUpdate> initialPresence = shard -> null;
    private Function<ShardInfo, SessionInfo> resumeOptions = shard -> null;
    private Possible<IntentSet> intents = Possible.absent();
    private boolean guildSubscriptions = true;
    private Function<GatewayDiscordClient, Mono<Void>> destroyHandler = shutdownDestroyHandler();
    private PayloadReader payloadReader = null;
    private PayloadWriter payloadWriter = null;
    private ReconnectOptions reconnectOptions = ReconnectOptions.create();
    private ReconnectOptions voiceReconnectOptions = ReconnectOptions.create();
    private GatewayObserver gatewayObserver = GatewayObserver.NOOP_LISTENER;
    private Function<ReactorResources, GatewayReactorResources> gatewayReactorResources = null;
    private Function<ReactorResources, VoiceReactorResources> voiceReactorResources = null;
    private VoiceConnectionFactory voiceConnectionFactory = defaultVoiceConnectionFactory();
    private EntityRetrievalStrategy entityRetrievalStrategy = null;
    private DispatchEventMapper dispatchEventMapper = null;
    private int maxMissedHeartbeatAck = 1;
    private Function<EventDispatcher, Publisher<?>> dispatcherFunction;

    /**
     * Create a default {@link GatewayBootstrap} based off the given {@link DiscordClient} that provides an instance
     * of {@link CoreResources} used to provide defaults while building a {@link GatewayDiscordClient}.
     *
     * @param client the {@link DiscordClient} used to set up configuration
     * @return a default builder to create {@link GatewayDiscordClient}
     */
    public static GatewayBootstrap<GatewayOptions> create(DiscordClient client) {
        return new GatewayBootstrap<>(client, Function.identity());
    }

    GatewayBootstrap(DiscordClient client, Function<GatewayOptions, O> optionsModifier) {
        this.client = client;
        this.optionsModifier = optionsModifier;
    }

    GatewayBootstrap(GatewayBootstrap<?> source, Function<GatewayOptions, O> optionsModifier) {
        this.optionsModifier = optionsModifier;

        this.client = source.client;
        this.shardingStrategy = source.shardingStrategy;
        this.awaitConnections = source.awaitConnections;
        this.shardCoordinator = source.shardCoordinator;
        this.eventDispatcher = source.eventDispatcher;
        this.storeService = source.storeService;
        this.invalidationStrategy = source.invalidationStrategy;
        this.memberRequestFilter = source.memberRequestFilter;
        this.initialPresence = source.initialPresence;
        this.resumeOptions = source.resumeOptions;
        this.intents = source.intents;
        this.guildSubscriptions = source.guildSubscriptions;
        this.destroyHandler = source.destroyHandler;
        this.payloadReader = source.payloadReader;
        this.payloadWriter = source.payloadWriter;
        this.reconnectOptions = source.reconnectOptions;
        this.voiceReconnectOptions = source.voiceReconnectOptions;
        this.gatewayObserver = source.gatewayObserver;
        this.gatewayReactorResources = source.gatewayReactorResources;
        this.voiceReactorResources = source.voiceReactorResources;
        this.voiceConnectionFactory = source.voiceConnectionFactory;
        this.entityRetrievalStrategy = source.entityRetrievalStrategy;
        this.dispatchEventMapper = source.dispatchEventMapper;
        this.maxMissedHeartbeatAck = source.maxMissedHeartbeatAck;
        this.dispatcherFunction = source.dispatcherFunction;
    }

    /**
     * Add a configuration for {@link GatewayClient} implementation-specific cases, changing the type of the current
     * {@link GatewayOptions} object passed to the {@link GatewayClient} factory in connect methods.
     *
     * @param optionsModifier {@link Function} to transform the {@link GatewayOptions} type to provide custom
     * {@link GatewayClient} implementations a proper configuration object.
     * @param <O2> new type for the options
     * @return a new {@link GatewayBootstrap} that will now work with the new options type.
     */
    public <O2 extends GatewayOptions> GatewayBootstrap<O2> setExtraOptions(Function<? super O, O2> optionsModifier) {
        return new GatewayBootstrap<>(this, this.optionsModifier.andThen(optionsModifier));
    }

    /**
     * Set the sharding method to use while building a {@link GatewayDiscordClient}. Defaults to creating all shards
     * given by the recommended amount from Discord. Built-in factories like {@link ShardingStrategy#fixed(int)} to use
     * a predefined number of shards, or customize the strategy using {@link ShardingStrategy#builder()}.
     * <p>
     * For example, it is possible to define the {@code shardCount} parameter independently from the number of shards
     * to create and connect to Gateway by using:
     * <pre>
     * .setSharding(ShardingStrategy.builder()
     *                 .indices(0, 2, 4)
     *                 .count(6)
     *                 .build())
     * </pre>
     * Would only connect shards 0, 2 and 4 while still indicating that your bot guilds are split across 6 shards.
     *
     * @param shardingStrategy a strategy to use while sharding the connections to Discord Gateway
     * @return this builder
     */
    public GatewayBootstrap<O> setSharding(ShardingStrategy shardingStrategy) {
        this.shardingStrategy = shardingStrategy;
        return this;
    }

    /**
     * Set if the connect {@link Mono} should defer completion until all joining shards have connected. Defaults to
     * {@code true} if running a single shard, otherwise {@code false}.
     *
     * @param awaitConnections {@code true} if connect should wait until all joining shards have connected before
     * completing, or {@code false} to complete immediately
     * @return this builder
     */
    public GatewayBootstrap<O> setAwaitConnections(boolean awaitConnections) {
        this.awaitConnections = awaitConnections;
        return this;
    }

    /**
     * Set a custom {@link ShardCoordinator} to manage multiple {@link GatewayDiscordClient} instances, even across
     * boundaries. Defaults to using {@link LocalShardCoordinator}.
     *
     * @param shardCoordinator an externally managed {@link ShardCoordinator} to coordinate multiple
     * {@link GatewayDiscordClient} instances.
     * @return this builder
     */
    public GatewayBootstrap<O> setShardCoordinator(ShardCoordinator shardCoordinator) {
        this.shardCoordinator = Objects.requireNonNull(shardCoordinator);
        return this;
    }

    /**
     * Set a custom {@link EventDispatcher} to receive {@link Event Events} from all joining shards and publish them to
     * all subscribers. Defaults to using {@link EventDispatcher#buffering()} which buffers all events until the
     * first subscriber subscribes to the dispatcher.
     *
     * @param eventDispatcher an externally managed {@link EventDispatcher} to publish events
     * @return this builder
     */
    public GatewayBootstrap<O> setEventDispatcher(@Nullable EventDispatcher eventDispatcher) {
        this.eventDispatcher = eventDispatcher;
        return this;
    }

    /**
     * Set a custom {@link StoreService}, an abstract factory to create {@link Store} instances, to cache Gateway
     * updates. Defaults to using {@link JdkStoreService} unless another factory of higher priority is discovered.
     *
     * @param storeService an externally managed {@link StoreService} to receive Gateway updates
     * @return this builder
     */
    public GatewayBootstrap<O> setStoreService(@Nullable StoreService storeService) {
        this.storeService = storeService;
        return this;
    }

    /**
     * Set a transformation function to modify or enrich the given or auto-detected {@link StoreService}.
     * <p>
     * Defaults to {@link #shardAwareStoreService()} that wraps the service with a {@link ShardAwareStoreService}
     * that is capable of tracking the shard index of a given entity and properly disposing them on shard invalidation.
     * To disable this behavior you can set {@link #identityStoreService()} as argument.
     *
     * @param storeServiceMapper a {@link Function} to transform a {@link StoreService}
     * @return this builder
     * @deprecated use {@link #setInvalidationStrategy(InvalidationStrategy)}
     */
    @Deprecated
    public GatewayBootstrap<O> setStoreServiceMapper(Function<StoreService, StoreService> storeServiceMapper) {
        return setInvalidationStrategy(new InvalidationStrategy() {
            @Override
            public StoreService adaptStoreService(StoreService storeService) {
                return storeServiceMapper.apply(storeService);
            }

            @Override
            public Mono<Void> invalidate(ShardInfo shardInfo, StateHolder stateHolder) {
                return stateHolder.invalidateStores();
            }
        });
    }

    /**
     * Set the {@link InvalidationStrategy} this shard group should use on shard session termination. Discord Gateway
     * sends real-time updates that are cached by Discord4J. When a Gateway session is terminated, any update beyond
     * that point is lost and therefore the cache, represented by the {@link Store} abstraction, is outdated. Reacting
     * to this event is called "invalidation" and can be configured through this method.
     * <p>
     * Defaults to using {@link InvalidationStrategy#disable()} unless you are under a single shard setup, where
     * {@link InvalidationStrategy#identity()} is used. Common possible options are:
     * <ul>
     *     <li>Using an in-memory registry through {@link InvalidationStrategy#withJdkRegistry()}</li>
     *     <li>For a custom registry use {@link InvalidationStrategy#withCustomRegistry(KeyStoreRegistry)}</li>
     *     <li>To disable this feature use {@link InvalidationStrategy#disable()}</li>
     *     <li>If this group only contains one shard, use {@link InvalidationStrategy#identity()}</li>
     * </ul>
     *
     * @param invalidationStrategy an {@link InvalidationStrategy} to apply to this shard group
     * @return this builder
     */
    public GatewayBootstrap<O> setInvalidationStrategy(InvalidationStrategy invalidationStrategy) {
        this.invalidationStrategy = Objects.requireNonNull(invalidationStrategy, "invalidationStrategy");
        return this;
    }

    /**
     * Set a {@link MemberRequestFilter} to determine how this shard group should request guild members. The provided
     * filter is applied on each GUILD_CREATE payload and if returns {@code true}, members will be requested for the
     * given guild. Defaults to loading members from all large guilds immediately after a GUILD_CREATE.
     *
     * @param memberRequestFilter the filter indicating how to load guild members
     * @return this builder
     * @see <a href="https://discord.com/developers/docs/topics/gateway#request-guild-members">Request Guild Members</a>
     */
    public GatewayBootstrap<O> setMemberRequestFilter(MemberRequestFilter memberRequestFilter) {
        this.memberRequestFilter = memberRequestFilter;
        return this;
    }

    /**
     * Set if this shard group should request large guild members from the Gateway.
     *
     * @param memberRequest {@code true} if enabling the large guild member requests, {@code false} otherwise
     * @return this builder
     * @see <a href="https://discord.com/developers/docs/topics/gateway#request-guild-members">Request Guild Members</a>
     * @deprecated use {@link #setMemberRequestFilter(MemberRequestFilter)}. Calling this method using {@code true} is
     * equivalent to using {@link MemberRequestFilter#withLargeGuilds()} and using {@code false} is the same as using
     * {@link MemberRequestFilter#none()}
     */
    @Deprecated
    public GatewayBootstrap<O> setMemberRequest(boolean memberRequest) {
        if (memberRequest) {
            this.memberRequestFilter = MemberRequestFilter.withLargeGuilds();
        } else {
            this.memberRequestFilter = MemberRequestFilter.none();
        }
        return this;
    }

    /**
     * Set a custom {@link Function handler} that generate a destroy sequence to be run once all joining shards have
     * disconnected, after all internal resources have been released. The destroy procedure is applied asynchronously
     * and errors are logged and swallowed. Defaults to {@link GatewayBootstrap#shutdownDestroyHandler()} that will
     * release the set {@link EventDispatcher} and {@link StoreService}.
     *
     * @param destroyHandler the {@link Function} supplying a {@link Mono} to reset state
     * @return this builder
     */
    public GatewayBootstrap<O> setDestroyHandler(Function<GatewayDiscordClient, Mono<Void>> destroyHandler) {
        this.destroyHandler = Objects.requireNonNull(destroyHandler, "destroyHandler");
        return this;
    }

    /**
     * Set a {@link Function} to determine the {@link StatusUpdate} that each joining shard should use when identifying
     * to the Gateway. Defaults to no status given.
     * <p>
     * {@link StatusUpdate} instances can be built through factories in {@link Presence}:
     * <ul>
     *     <li>{@link Presence#online()} and {@link Presence#online(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#idle()} and {@link Presence#idle(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#doNotDisturb()} and {@link Presence#doNotDisturb(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#invisible()}</li>
     * </ul>
     *
     * @param initialPresence a {@link Function} that supplies {@link StatusUpdate} instances from a given
     * {@link ShardInfo}
     * @return this builder
     * @deprecated use {@link #setInitialStatus(Function)}
     */
    @Deprecated
    public GatewayBootstrap<O> setInitialPresence(Function<ShardInfo, StatusUpdate> initialPresence) {
        this.initialPresence = Objects.requireNonNull(initialPresence, "initialPresence");
        return this;
    }

    /**
     * Set a {@link Function} to determine the {@link StatusUpdate} that each joining shard should use when identifying
     * to the Gateway. Defaults to no status given.
     * <p>
     * {@link StatusUpdate} instances can be built through factories in {@link Presence}:
     * <ul>
     *     <li>{@link Presence#online()} and {@link Presence#online(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#idle()} and {@link Presence#idle(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#doNotDisturb()} and {@link Presence#doNotDisturb(ActivityUpdateRequest)}</li>
     *     <li>{@link Presence#invisible()}</li>
     * </ul>
     *
     * @param initialStatus a {@link Function} that supplies {@link StatusUpdate} instances from a given
     * {@link ShardInfo}
     * @return this builder
     */
    public GatewayBootstrap<O> setInitialStatus(Function<ShardInfo, StatusUpdate> initialStatus) {
        this.initialPresence = Objects.requireNonNull(initialStatus, "initialStatus");
        return this;
    }

    /**
     * Set a {@link Function} to determine the details to resume a session that each joining shard should use when
     * identifying for the first time to the Gateway. Defaults to returning {@code null} to begin a fresh session on
     * startup.
     *
     * @param resumeOptions a {@link Function} that supplies {@link SessionInfo} instances from a given
     * {@link ShardInfo}
     * @return this builder
     */
    public GatewayBootstrap<O> setResumeOptions(Function<ShardInfo, SessionInfo> resumeOptions) {
        this.resumeOptions = Objects.requireNonNull(resumeOptions, "resumeOptions");
        return this;
    }

    /**
     * Set the intents to subscribe from the gateway for this shard.
     * Intents will not be used, when this method is not called.
     *
     * @param intents set of intents to subscribe
     * @return this builder
     */
    public GatewayBootstrap<O> setEnabledIntents(IntentSet intents) {
        this.intents = Possible.of(intents);
        return this;
    }

    /**
     * Set the intents which should not be subscribed from the gateway for this shard.
     * This method computes by {@code IntentSet.all()} - the provided intents
     * Intents will not be used, when this method is not called.
     *
     * @param intents set of intents which should not be subscribed
     * @return this builder
     */
    public GatewayBootstrap<O> setDisabledIntents(IntentSet intents) {
        this.intents = Possible.of(IntentSet.all().andNot(intents));
        return this;
    }

    /**
     * Set if this shard group will subscribe to presence and typing events. Defaults to {@code true}.
     *
     * @param guildSubscriptions whether to enable or disable guild subscriptions
     * @return this builder
     * @see <a href="https://discord.com/developers/docs/topics/gateway#guild-subscriptions">Guild Subscriptions</a>
     */
    public GatewayBootstrap<O> setGuildSubscriptions(boolean guildSubscriptions) {
        this.guildSubscriptions = guildSubscriptions;
        return this;
    }

    /**
     * Customize how inbound Gateway payloads are decoded from {@link ByteBuf}.
     *
     * @param payloadReader a Gateway payload decoder
     * @return this builder
     */
    public GatewayBootstrap<O> setPayloadReader(@Nullable PayloadReader payloadReader) {
        this.payloadReader = payloadReader;
        return this;
    }

    /**
     * Customize how outbound Gateway payloads are encoded into {@link ByteBuf}.
     *
     * @param payloadWriter a Gateway payload encoder
     * @return this builder
     */
    public GatewayBootstrap<O> setPayloadWriter(@Nullable PayloadWriter payloadWriter) {
        this.payloadWriter = payloadWriter;
        return this;
    }

    /**
     * Set a custom {@link ReconnectOptions} to configure how Gateway connections will attempt to reconnect every
     * time a websocket session is closed unexpectedly.
     *
     * @param reconnectOptions a {@link ReconnectOptions} policy to use in Gateway connections
     * @return this builder
     */
    public GatewayBootstrap<O> setReconnectOptions(ReconnectOptions reconnectOptions) {
        this.reconnectOptions = Objects.requireNonNull(reconnectOptions);
        return this;
    }

    /**
     * Set a custom {@link ReconnectOptions} to configure how Voice Gateway connections will attempt to reconnect every
     * time a websocket session is closed unexpectedly.
     *
     * @param voiceReconnectOptions a {@link ReconnectOptions} policy to use in Voice Gateway connections
     * @return this builder
     */
    public GatewayBootstrap<O> setVoiceReconnectOptions(ReconnectOptions voiceReconnectOptions) {
        this.voiceReconnectOptions = Objects.requireNonNull(voiceReconnectOptions);
        return this;
    }

    /**
     * Set a custom {@link GatewayObserver} to be notified of Gateway lifecycle events across all joining shards.
     *
     * @param gatewayObserver a {@link GatewayObserver} to install on all joining shards
     * @return this builder
     */
    public GatewayBootstrap<O> setGatewayObserver(GatewayObserver gatewayObserver) {
        this.gatewayObserver = Objects.requireNonNull(gatewayObserver);
        return this;
    }

    /**
     * Customize the {@link ReactorResources} used exclusively for Gateway-related operations, such as maintaining
     * the websocket connections and scheduling Gateway tasks. Defaults to using the parent {@link ReactorResources}
     * inherited from {@link DiscordClient}.
     *
     * @param gatewayReactorResources a {@link ReactorResources} object for Gateway operations
     * @return this builder
     */
    public GatewayBootstrap<O> setGatewayReactorResources(Function<ReactorResources, GatewayReactorResources> gatewayReactorResources) {
        this.gatewayReactorResources = Objects.requireNonNull(gatewayReactorResources);
        return this;
    }

    /**
     * Customize the {@link ReactorResources} used exclusively for voice-related operations, such as maintaining
     * the Voice Gateway websocket connections, Voice UDP socket connections and scheduling Gateway tasks. Defaults
     * to using the parent {@link ReactorResources} inherited from {@link DiscordClient}.
     *
     * @param voiceReactorResources a {@link ReactorResources} object for voice operations
     * @return this builder
     */
    public GatewayBootstrap<O> setVoiceReactorResources(Function<ReactorResources, VoiceReactorResources> voiceReactorResources) {
        this.voiceReactorResources = Objects.requireNonNull(voiceReactorResources);
        return this;
    }

    /**
     * Customize the {@link VoiceConnectionFactory} used to establish and maintain {@link VoiceConnection} instances to
     * perform voice-related operations. Defaults to {@link #defaultVoiceConnectionFactory()}.
     *
     * @param voiceConnectionFactory a factory that can create {@link VoiceConnection} instances.
     * @return this builder
     */
    public GatewayBootstrap<O> setVoiceConnectionFactory(VoiceConnectionFactory voiceConnectionFactory) {
        this.voiceConnectionFactory = Objects.requireNonNull(voiceConnectionFactory);
        return this;
    }

    /**
     * Customize the {@link EntityRetrievalStrategy} to use by default in order to retrieve Discord entities.
     *
     * @param entityRetrievalStrategy a strategy to use to retrieve entities
     * @return this builder
     */
    public GatewayBootstrap<O> setEntityRetrievalStrategy(@Nullable EntityRetrievalStrategy entityRetrievalStrategy) {
        this.entityRetrievalStrategy = entityRetrievalStrategy;
        return this;
    }

    /**
     * Customize the {@link DispatchEventMapper} used to convert Gateway Dispatch into {@link Event} instances.
     * Defaults to using {@link DispatchEventMapper#emitEvents()} that will process payloads and save its updates to
     * the appropriate {@link Store}, then generate the right {@link Event} instance.
     *
     * @param dispatchEventMapper a factory to derive {@link Event Events} from Gateway
     * @return this builder
     */
    public GatewayBootstrap<O> setDispatchEventMapper(DispatchEventMapper dispatchEventMapper) {
        this.dispatchEventMapper = Objects.requireNonNull(dispatchEventMapper);
        return this;
    }

    /**
     * Set the maximum number of missed heartbeat acknowledge payloads each connection to Gateway will allow before
     * triggering an automatic reconnect. A missed acknowledge is counted if a client does not receive a heartbeat
     * ACK between its attempts at sending heartbeats.
     *
     * @param maxMissedHeartbeatAck a non-negative number representing the maximum number of allowed
     * @return this builder
     */
    public GatewayBootstrap<O> setMaxMissedHeartbeatAck(int maxMissedHeartbeatAck) {
        this.maxMissedHeartbeatAck = Math.max(0, maxMissedHeartbeatAck);
        return this;
    }

    /**
     * Set an initial subscriber to the bootstrapped {@link EventDispatcher} to gain access to early startup events. The
     * subscriber is derived from the given {@link Function} which returns a {@link Publisher} that is subscribed early
     * in the Gateway connection process.
     *
     * @param dispatcherFunction an {@link EventDispatcher} mapper that derives an asynchronous listener
     * @return this builder
     */
    @Experimental
    public GatewayBootstrap<O> withEventDispatcher(Function<EventDispatcher, Publisher<?>> dispatcherFunction) {
        this.dispatcherFunction = Objects.requireNonNull(dispatcherFunction);
        return this;
    }

    /**
     * Connect to the Discord Gateway upon subscription to acquire a {@link GatewayDiscordClient} instance and use it
     * in a declarative way, releasing the object once the derived usage {@link Function} completes, and the underlying
     * shard group disconnects, according to {@link GatewayDiscordClient#onDisconnect()}.
     * <p>
     * The timing of acquiring a {@link GatewayDiscordClient} depends on the {@link #setAwaitConnections(boolean)}
     * setting: if {@code true}, when all joining shards have connected; if {@code false}, as soon as it is possible to
     * establish a connection to the Gateway.
     * <p>
     * Calling this method is useful when you operate on the {@link GatewayDiscordClient} object using reactive API you
     * can compose within the scope of the given {@link Function}.
     *
     * @param whileConnectedFunction the {@link Function} to apply the <strong>connected</strong>
     * {@link GatewayDiscordClient} and trigger a processing pipeline from it.
     * @return an empty {@link Mono} completing after all resources have released
     */
    public Mono<Void> withGateway(Function<GatewayDiscordClient, Publisher<?>> whileConnectedFunction) {
        return usingConnection(gateway -> Flux.from(whileConnectedFunction.apply(gateway)).then(gateway.onDisconnect()));
    }

    /**
     * Connect to the Discord Gateway upon subscription to acquire a {@link GatewayDiscordClient} instance and use it
     * in a declarative way, releasing the object once the derived usage {@link Function} completes, and the underlying
     * shard group disconnects, according to {@link GatewayDiscordClient#onDisconnect()}.
     * <p>
     * The timing of acquiring a {@link GatewayDiscordClient} depends on the {@link #setAwaitConnections(boolean)}
     * setting: if {@code true}, when all joining shards have connected; if {@code false}, as soon as it is possible to
     * establish a connection to the Gateway.
     * <p>
     * Calling this method is useful when you operate on the {@link GatewayDiscordClient} object using reactive API you
     * can compose within the scope of the given {@link Function}.
     *
     * @param whileConnectedFunction the {@link Function} to apply the <strong>connected</strong>
     * {@link GatewayDiscordClient} and trigger a processing pipeline from it.
     * @return an empty {@link Mono} completing after all resources have released
     * @deprecated use {@link #withGateway(Function)}
     */
    @Deprecated
    public Mono<Void> withConnection(Function<GatewayDiscordClient, Mono<Void>> whileConnectedFunction) {
        return usingConnection(gateway -> whileConnectedFunction.apply(gateway).then(gateway.onDisconnect()));
    }

    private <T> Mono<T> usingConnection(Function<GatewayDiscordClient, Mono<T>> onConnectedFunction) {
        return Mono.usingWhen(connect(), onConnectedFunction, GatewayDiscordClient::logout);
    }

    /**
     * Connect to the Discord Gateway upon subscription to build a {@link GatewayClient} from the set of options
     * configured by this builder. The resulting {@link GatewayDiscordClient} can be externally managed, leaving you
     * in charge of properly releasing its resources by calling {@link GatewayDiscordClient#logout()}.
     * <p>
     * The timing of acquiring a {@link GatewayDiscordClient} depends on the {@link #setAwaitConnections(boolean)}
     * setting: if {@code true}, when all joining shards have connected; if {@code false}, as soon as it is possible
     * to establish a connection to the Gateway.
     * <p>
     * All joining shards will attempt to serially connect to Discord Gateway, coordinated by the current
     * {@link ShardCoordinator}. If one of the shards fail to connect due to a retryable problem like invalid session
     * it will retry before continuing to the next one.
     *
     * @return a {@link Mono} that upon subscription and depending on the configuration of
     * {@link #setAwaitConnections(boolean)}, emits a {@link GatewayDiscordClient}. If an error occurs during the setup
     * sequence, it will be emitted through the {@link Mono}.
     */
    public Mono<GatewayDiscordClient> login() {
        return connect(DefaultGatewayClient::new);
    }

    /**
     * Connect to the Discord Gateway upon subscription to build a {@link GatewayClient} from the set of options
     * configured by this builder. The resulting {@link GatewayDiscordClient} can be externally managed, leaving you
     * in charge of properly releasing its resources by calling {@link GatewayDiscordClient#logout()}.
     * <p>
     * The timing of acquiring a {@link GatewayDiscordClient} depends on the {@link #setAwaitConnections(boolean)}
     * setting: if {@code true}, when all joining shards have connected; if {@code false}, as soon as it is possible
     * to establish a connection to the Gateway.
     * <p>
     * All joining shards will attempt to serially connect to Discord Gateway, coordinated by the current
     * {@link ShardCoordinator}. If one of the shards fail to connect due to a retryable problem like invalid session
     * it will retry before continuing to the next one.
     *
     * @return a {@link Mono} that upon subscription and depending on the configuration of
     * {@link #setAwaitConnections(boolean)}, emits a {@link GatewayDiscordClient}. If an error occurs during the setup
     * sequence, it will be emitted through the {@link Mono}.
     * @deprecated use {@link #login()}
     */
    @Deprecated
    public Mono<GatewayDiscordClient> connect() {
        return connect(DefaultGatewayClient::new);
    }

    /**
     * Connect to the Discord Gateway upon subscription using a custom {@link Function factory} to build a
     * {@link GatewayClient} from the set of options configured by this builder. See {@link #connect()} for more details
     * about how the returned {@link Mono} operates.
     *
     * @return a {@link Mono} that upon subscription and depending on the configuration of
     * {@link #setAwaitConnections(boolean)}, emits a {@link GatewayDiscordClient}. If an error occurs during the setup
     * sequence, it will be emitted through the {@link Mono}.
     */
    public Mono<GatewayDiscordClient> login(Function<O, GatewayClient> clientFactory) {
        return connect(clientFactory);
    }

    /**
     * Connect to the Discord Gateway upon subscription using a custom {@link Function factory} to build a
     * {@link GatewayClient} from the set of options configured by this builder. See {@link #connect()} for more details
     * about how the returned {@link Mono} operates.
     *
     * @return a {@link Mono} that upon subscription and depending on the configuration of
     * {@link #setAwaitConnections(boolean)}, emits a {@link GatewayDiscordClient}. If an error occurs during the setup
     * sequence, it will be emitted through the {@link Mono}.
     * @deprecated use {@link #login(Function)}
     */
    @Deprecated
    public Mono<GatewayDiscordClient> connect(Function<O, GatewayClient> clientFactory) {
        GatewayBootstrap<O> b = new GatewayBootstrap<>(this, this.optionsModifier);
        return b.shardingStrategy.getShardCount(b.client)
                .flatMap(count -> {
                    InvalidationStrategy invalidationStrategy = b.initInvalidationStrategy(count);
                    Map<String, Object> hints = new LinkedHashMap<>();
                    hints.put("messageClass", MessageData.class);
                    StateHolder stateHolder = new StateHolder(b.initStoreService(invalidationStrategy),
                            new StoreContext(hints));
                    StateView stateView = new StateView(stateHolder);
                    EventDispatcher eventDispatcher = b.initEventDispatcher();
                    GatewayReactorResources gatewayReactorResources = b.initGatewayReactorResources();
                    ShardCoordinator shardCoordinator = b.initShardCoordinator(gatewayReactorResources);
                    GatewayResources resources = new GatewayResources(stateView, eventDispatcher, shardCoordinator,
                            b.memberRequestFilter, gatewayReactorResources, b.initVoiceReactorResources(),
                            b.voiceReconnectOptions, b.intents);
                    MonoProcessor<Void> closeProcessor = MonoProcessor.create();
                    EntityRetrievalStrategy entityRetrievalStrategy = b.initEntityRetrievalStrategy();
                    DispatchEventMapper dispatchMapper = b.initDispatchEventMapper();

                    GatewayClientGroupManager clientGroup = b.shardingStrategy.getGroupManager(count);
                    GatewayDiscordClient gateway = new GatewayDiscordClient(b.client, resources, closeProcessor,
                            clientGroup, b.voiceConnectionFactory, entityRetrievalStrategy);

                    Flux<ShardInfo> connections = b.shardingStrategy.getShards(count)
                            .groupBy(shard -> shard.getIndex() % b.shardingStrategy.getShardingFactor())
                            .flatMap(group -> group.concatMap(shard -> acquireConnection(b, shard, clientFactory,
                                    gateway, shardCoordinator, stateHolder, eventDispatcher, clientGroup,
                                    closeProcessor, dispatchMapper, invalidationStrategy)));

                    if (b.awaitConnections == null ? count == 1 : b.awaitConnections) {
                        if (b.dispatcherFunction != null) {
                            return Mono.create(sink -> {
                                Disposable.Composite cleanup = Disposables.composite();
                                cleanup.add(Flux.from(b.dispatcherFunction.apply(eventDispatcher))
                                        .subscribeOn(gatewayReactorResources.getBlockingTaskScheduler())
                                        .subscribe(null, t -> log.warn("Error in dispatcher function", t)));
                                cleanup.add(connections.then(Mono.just(gateway))
                                        .subscribe(sink::success, sink::error));
                                sink.onCancel(cleanup);
                            });
                        }
                        return connections.then(Mono.just(gateway));
                    } else {
                        if (b.dispatcherFunction != null) {
                            return Mono.create(sink -> {
                                Disposable.Composite cleanup = Disposables.composite();
                                cleanup.add(Flux.from(b.dispatcherFunction.apply(eventDispatcher))
                                        .subscribeOn(gatewayReactorResources.getBlockingTaskScheduler())
                                        .subscribe(null, t -> log.warn("Error in dispatcher function", t)));
                                cleanup.add(connections.subscribe(__ -> sink.success(gateway), sink::error));
                                sink.onCancel(cleanup);
                            });
                        }
                        return Mono.create(sink ->
                                sink.onCancel(connections.subscribe(__ -> sink.success(gateway), sink::error)));
                    }
                });
    }

    private Mono<ShardInfo> acquireConnection(GatewayBootstrap<O> b,
                                              ShardInfo shard,
                                              Function<O, GatewayClient> clientFactory,
                                              GatewayDiscordClient gateway,
                                              ShardCoordinator shardCoordinator,
                                              StateHolder stateHolder,
                                              EventDispatcher eventDispatcher,
                                              GatewayClientGroupManager clientGroup,
                                              MonoProcessor<Void> closeProcessor,
                                              DispatchEventMapper dispatchMapper,
                                              InvalidationStrategy invalidationStrategy) {
        return Mono.deferWithContext(ctx ->
                Mono.<ShardInfo>create(sink -> {
                    StatusUpdate initial = Optional.ofNullable(b.initialPresence.apply(shard)).orElse(null);
                    IdentifyOptions identify = new IdentifyOptions(shard, initial, b.intents, b.guildSubscriptions);
                    SessionInfo resume = b.resumeOptions.apply(shard);
                    if (resume != null) {
                        identify.setResumeSessionId(resume.getId());
                        identify.setResumeSequence(resume.getSequence());
                    }
                    PayloadTransformer limiter = shardCoordinator.getIdentifyLimiter(shard,
                            b.shardingStrategy.getShardingFactor());
                    Disposable.Composite forCleanup = Disposables.composite();
                    GatewayClient gatewayClient = clientFactory.apply(buildOptions(gateway, identify, limiter));
                    clientGroup.add(shard.getIndex(), gatewayClient);

                    // wire gateway events to EventDispatcher
                    forCleanup.add(gatewayClient.dispatch()
                            .takeUntilOther(closeProcessor)
                            .checkpoint("Read payload from gateway")
                            .flatMap(dispatch -> {
                                ShardInfo info;
                                Dispatch actual;
                                if (dispatch instanceof ShardAwareDispatch) {
                                    ShardAwareDispatch shardDispatch = (ShardAwareDispatch) dispatch;
                                    info = ShardInfo.create(shardDispatch.getShardIndex(),
                                            shardDispatch.getShardCount());
                                    actual = shardDispatch.getDispatch();
                                } else {
                                    info = shard;
                                    actual = dispatch;
                                }
                                return dispatchMapper.handle(DispatchContext.of(actual, gateway, stateHolder, info))
                                        .subscriberContext(c -> c.put(LogUtil.KEY_SHARD_ID, info.getIndex()))
                                        .onErrorResume(error -> {
                                            log.error(format(ctx, "Error dispatching event"), error);
                                            return Mono.empty();
                                        });
                            })
                            .doOnNext(eventDispatcher::publish)
                            .subscribe(null,
                                    t -> log.error(format(ctx, "Event mapper terminated with an error"), t),
                                    () -> log.debug(format(ctx, "Event mapper completed"))));

                    // wire internal shard coordinator events
                    // TODO: transition into separate lifecycleSink for these events
                    forCleanup.add(gatewayClient.dispatch()
                            .ofType(GatewayStateChange.class)
                            .takeUntilOther(closeProcessor)
                            .map(dispatch -> DispatchContext.of(dispatch, gateway, stateHolder, shard))
                            .flatMap(context -> {
                                GatewayStateChange event = context.getDispatch();
                                SessionInfo session = null;
                                switch (event.getState()) {
                                    case CONNECTED:
                                        log.info(format(ctx, "Shard connected"));
                                        return shardCoordinator.publishConnected(shard)
                                                .doFinally(__ -> sink.success(shard));
                                    case DISCONNECTED_RESUME:
                                        session = new SessionInfo(gatewayClient.getSessionId(),
                                                gatewayClient.getSequence());
                                    case DISCONNECTED:
                                        log.info(format(ctx, "Shard disconnected"));
                                        return shardCoordinator.publishDisconnected(shard, session)
                                                .then(invalidationStrategy.invalidate(shard, stateHolder))
                                                .then(Mono.fromRunnable(() -> clientGroup.remove(shard.getIndex())))
                                                .then(shardCoordinator.getConnectedCount()
                                                        .filter(count -> count == 0 && !closeProcessor.isDisposed())
                                                        .flatMap(__ -> {
                                                            log.info(format(ctx, "All shards disconnected"));
                                                            return destroyHandler.apply(gateway)
                                                                    .doOnTerminate(closeProcessor::onComplete);
                                                        }))
                                                .onErrorResume(t -> {
                                                    log.warn(format(ctx, "Error while releasing resources"), t);
                                                    return Mono.empty();
                                                });
                                    case RETRY_STARTED:
                                    case RETRY_FAILED:
                                        log.debug(format(ctx, "Invalidating stores for shard"));
                                        return invalidationStrategy.invalidate(shard, stateHolder);
                                }
                                return Mono.empty();
                            })
                            .subscriberContext(buildContext(gateway, shard))
                            .subscribe(null,
                                    t -> log.error(format(ctx, "Lifecycle listener terminated with an error"), t),
                                    () -> log.debug(format(ctx, "Lifecycle listener completed"))));

                    forCleanup.add(b.client.getGatewayService()
                            .getGateway()
                            .doOnSubscribe(s -> log.debug(format(ctx, "Acquiring gateway endpoint")))
                            .retryBackoff(b.reconnectOptions.getMaxRetries(),
                                    b.reconnectOptions.getFirstBackoff(),
                                    b.reconnectOptions.getMaxBackoffInterval())
                            .flatMap(response -> gatewayClient.execute(
                                    RouteUtils.expandQuery(response.url(), getGatewayParameters())))
                            .doOnError(sink::error) // only useful for startup errors
                            .doFinally(__ -> {
                                sink.success(); // no-op if we completed it before
                                closeProcessor.onComplete();
                            })
                            .subscriberContext(buildContext(gateway, shard))
                            .subscribe(null,
                                    t -> log.debug(format(ctx, "Gateway terminated with an error: {}"), t),
                                    () -> log.debug(format(ctx, "Gateway completed"))));

                    sink.onCancel(forCleanup);
                }))
                .subscriberContext(buildContext(gateway, shard));
    }

    private Function<Context, Context> buildContext(GatewayDiscordClient gateway, ShardInfo shard) {
        return ctx -> ctx.put(LogUtil.KEY_GATEWAY_ID, Integer.toHexString(gateway.hashCode()))
                .put(LogUtil.KEY_SHARD_ID, shard.getIndex());
    }

    private PayloadReader initPayloadReader() {
        if (payloadReader != null) {
            return payloadReader;
        }
        return new JacksonPayloadReader(client.getCoreResources().getJacksonResources().getObjectMapper());
    }

    private PayloadWriter initPayloadWriter() {
        if (payloadWriter != null) {
            return payloadWriter;
        }
        return new JacksonPayloadWriter(client.getCoreResources().getJacksonResources().getObjectMapper());
    }

    private GatewayReactorResources initGatewayReactorResources() {
        if (gatewayReactorResources == null) {
            gatewayReactorResources = GatewayReactorResources::new;
        }
        return gatewayReactorResources.apply(client.getCoreResources().getReactorResources());
    }

    private VoiceReactorResources initVoiceReactorResources() {
        if (voiceReactorResources == null) {
            voiceReactorResources = VoiceReactorResources::new;
        }
        return voiceReactorResources.apply(client.getCoreResources().getReactorResources());
    }

    private EventDispatcher initEventDispatcher() {
        if (eventDispatcher != null) {
            return eventDispatcher;
        }
        return EventDispatcher.buffering();
    }

    private ShardCoordinator initShardCoordinator(ReactorResources reactorResources) {
        if (shardCoordinator != null) {
            return shardCoordinator;
        }
        return LocalShardCoordinator.create(() ->
                new RateLimitTransformer(1, Duration.ofSeconds(6), reactorResources.getTimerTaskScheduler()));
    }

    private StoreService initStoreService(InvalidationStrategy invalidationStrategy) {
        if (storeService == null) {
            Map<Class<? extends StoreService>, Integer> priority = new HashMap<>();
            // We want almost minimum priority, so that jdk can beat no-op, but most implementations will beat jdk
            priority.put(JdkStoreService.class, Integer.MAX_VALUE - 1);
            StoreServiceLoader storeServiceLoader = new StoreServiceLoader(priority);
            storeService = storeServiceLoader.getStoreService();
            if (storeService instanceof ForwardingStoreService) {
                ForwardingStoreService forwarding = (ForwardingStoreService) storeService;
                StoreService delegate = forwarding.getOriginal();
                if (!(delegate instanceof JdkStoreService)) {
                    log.info("Found StoreService: {}", delegate);
                }
            } else {
                log.info("Found StoreService: {}", storeService);
            }
        }
        return invalidationStrategy.adaptStoreService(storeService.hasLongObjStores() ?
                storeService : new ForwardingStoreService(storeService));
    }

    private EntityRetrievalStrategy initEntityRetrievalStrategy() {
        if (entityRetrievalStrategy != null) {
            return entityRetrievalStrategy;
        }
        return EntityRetrievalStrategy.STORE_FALLBACK_REST;
    }

    private DispatchEventMapper initDispatchEventMapper() {
        if (dispatchEventMapper != null) {
            return dispatchEventMapper;
        }
        return DispatchEventMapper.emitEvents();
    }

    private InvalidationStrategy initInvalidationStrategy(int shardCount) {
        if (invalidationStrategy != null) {
            return invalidationStrategy;
        }
        return shardCount == 1 ? InvalidationStrategy.identity() : InvalidationStrategy.disable();
    }

    private O buildOptions(GatewayDiscordClient gateway, IdentifyOptions identify, PayloadTransformer identifyLimiter) {
        GatewayOptions options = new GatewayOptions(client.getCoreResources().getToken(),
                gateway.getGatewayResources().getGatewayReactorResources(), initPayloadReader(), initPayloadWriter(),
                reconnectOptions, identify, gatewayObserver, identifyLimiter, maxMissedHeartbeatAck);
        return this.optionsModifier.apply(options);
    }

    private Map<String, Object> getGatewayParameters() {
        final Map<String, Object> parameters = new HashMap<>(3);
        parameters.put("compress", "zlib-stream");
        parameters.put("encoding", "json");
        parameters.put("v", 6);
        return parameters;
    }

    /**
     * Destroy handler that doesn't perform any cleanup task.
     *
     * @return a noop destroy handler
     */
    public static Function<GatewayDiscordClient, Mono<Void>> noopDestroyHandler() {
        return gateway -> Mono.empty();
    }


    /**
     * Destroy handler that calls {@link EventDispatcher#shutdown()} followed by {@link StoreService#dispose()}
     * asynchronously.
     *
     * @return a shutdown destroy handler
     */
    public static Function<GatewayDiscordClient, Mono<Void>> shutdownDestroyHandler() {
        return gateway -> {
            gateway.getEventDispatcher().shutdown();
            return gateway.getGatewayResources().getStateView().getStoreService().dispose();
        };
    }

    /**
     * A {@link StoreService} mapper that doesn't modify the input.
     *
     * @return a noop {@link StoreService} mapper
     * @deprecated use {@link IdentityInvalidationStrategy} if you want to disable store invalidation
     */
    @Deprecated
    public static Function<StoreService, StoreService> identityStoreService() {
        return storeService -> storeService;
    }

    /**
     * A {@link StoreService} mapper that will wrap the input with a {@link ShardAwareStoreService} using a
     * {@link JdkKeyStoreRegistry} that will track shard index of saved entities to allow for cleanup on shard
     * invalidation.
     *
     * @return a shard-aware {@link StoreService} mapper
     * @deprecated use {@link KeyStoreInvalidationStrategy} to use a dedicated key store to invalidate the store on
     * shard invalidation
     */
    @Deprecated
    public static Function<StoreService, StoreService> shardAwareStoreService() {
        return storeService -> new ShardAwareStoreService(new JdkKeyStoreRegistry(), storeService);
    }

    /**
     * Create a {@link VoiceConnectionFactory} with reconnecting capabilities.
     *
     * @return a default {@link VoiceConnectionFactory}
     */
    public static VoiceConnectionFactory defaultVoiceConnectionFactory() {
        return new DefaultVoiceConnectionFactory();
    }

}
