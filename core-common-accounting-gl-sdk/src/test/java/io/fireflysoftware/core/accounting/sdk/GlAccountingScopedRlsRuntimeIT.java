/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.accounting.sdk;

import static io.fireflysoftware.core.accounting.sdk.GlNativeAccountingSchemaIT.*;
import static io.fireflysoftware.core.accounting.sdk.GlNativeJournalAtomicSchemaIT.*;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.services.posting.v1.*;
import io.r2dbc.pool.*;
import io.r2dbc.postgresql.*;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.*;
import java.time.*;
import java.util.*;

@Testcontainers
class GlAccountingScopedRlsRuntimeIT {
    @Container
    static PostgreSQLContainer dbOwner =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_scoped_rls")
                    .withUsername("gl_migration")
                    .withPassword("synthetic-only")
                    .withCreateContainerCmdModifier(
                            c ->
                                    c.getHostConfig()
                                            .withPortBindings(
                                                    new com.github.dockerjava.api.model.PortBinding(
                                                            com.github.dockerjava.api.model.Ports
                                                                    .Binding.bindIp("127.0.0.1"),
                                                            new com.github.dockerjava.api.model
                                                                    .ExposedPort(5432))));

    static ConnectionPool pool;
    static DatabaseClient db;
    static GlAccountingTransaction transactions;

    @BeforeAll
    static void setup() throws Exception {
        Flyway.configure()
                .dataSource(dbOwner.getJdbcUrl(), dbOwner.getUsername(), dbOwner.getPassword())
                .locations(System.getProperty("gl.migration.location", "classpath:db/migration"))
                .load()
                .migrate();
        try (var c = ownerConnection()) {
            execute(
                    c,
                    "CREATE ROLE gl_scoped_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER"
                        + " NOBYPASSRLS NOCREATEROLE NOCREATEDB;REVOKE CREATE ON SCHEMA public FROM"
                        + " PUBLIC;GRANT USAGE ON SCHEMA public TO gl_scoped_app;GRANT"
                        + " SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO"
                        + " gl_scoped_app");
        }
        pool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(
                                        new PostgresqlConnectionFactory(
                                                PostgresqlConnectionConfiguration.builder()
                                                        .host(dbOwner.getHost())
                                                        .port(dbOwner.getMappedPort(5432))
                                                        .database(dbOwner.getDatabaseName())
                                                        .username("gl_scoped_app")
                                                        .password("synthetic-only")
                                                        .build()))
                                .initialSize(0)
                                .maxSize(2)
                                .build());
        db = DatabaseClient.create(pool);
        transactions =
                new GlAccountingTransaction(
                        new R2dbcTransactionManager(pool), new GlAccountingDatabaseContext(db));
    }

    @AfterAll
    static void close() {
        if (pool != null) pool.dispose();
    }

    static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                dbOwner.getJdbcUrl(), dbOwner.getUsername(), dbOwner.getPassword());
    }

    record Data(Fixture fixture, Effect effect) {}

    static Data data() throws Exception {
        try (var c = ownerConnection()) {
            var fixture = fixture(c);
            var effect = effect(c, false);
            c.setAutoCommit(false);
            complete(c, fixture, effect, 1, null, "12500");
            c.commit();
            return new Data(fixture, effect);
        }
    }

    static GlAccountingAuthority authority(Fixture f) {
        var beans = new StaticListableBeanFactory();
        beans.addBean(
                "actor",
                (SecurityContextPort)
                        () ->
                                Mono.just(
                                        SecurityPrincipal.builder()
                                                .subject("synthetic-scoped-gl-operator")
                                                .issuer("https://synthetic-scoped-gl.invalid")
                                                .claims(
                                                        Map.of(
                                                                "exp",
                                                                Instant.now().plusSeconds(60)))
                                                .build()));
        var tenant = f.scope().tenant().toString();
        var book = f.scope().book().toString();
        beans.addBean(
                "policy",
                (PolicyDecisionPort)
                        (actor, action, resource, context) ->
                                Mono.just(
                                        tenant.equals(context.get("tenantId"))
                                                        && book.equals(context.get("bookId"))
                                                        && Set.of(
                                                                        "accounting/book/" + book,
                                                                        "accounting/account/"
                                                                                + f.debit(),
                                                                        "accounting/account/"
                                                                                + f.credit())
                                                                .contains(resource)
                                                ? Decision.permit()
                                                : Decision.deny("Synthetic exact fixture denied")));
        beans.addBean(
                "approval",
                (AccountingApprovalPort)
                        (actor, intent) ->
                                Mono.just(
                                        new AccountingApproval(
                                                tenant,
                                                book,
                                                intent.operation(),
                                                intent.intentDigest(),
                                                actor.issuer(),
                                                actor.subject(),
                                                "https://synthetic-scoped-gl-approval.invalid",
                                                "synthetic-scoped-approval",
                                                "b".repeat(64),
                                                Instant.now().plusSeconds(45).toEpochMilli())));
        return new GlAccountingAuthority(
                beans.getBeanProvider(SecurityContextPort.class),
                beans.getBeanProvider(PolicyDecisionPort.class),
                beans.getBeanProvider(AccountingApprovalPort.class),
                beans.getBeanProvider(AccountingBookOwnerPort.class),
                new MockEnvironment()
                        .withProperty("firefly.security.resource-server.strict-policy", "true")
                        .withProperty(
                                "firefly.accounting.approval.trusted-issuers",
                                "https://synthetic-scoped-gl-approval.invalid"));
    }

    static GlAccountingAuthority.Admission read(
            Data data, Collection<String> accounts, String operation) {
        var f = data.fixture();
        var posting = data.effect().posting().toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_READ_CONTEXT_V1",
                        List.of(
                                f.scope().tenant().toString(),
                                f.scope().book().toString(),
                                operation,
                                posting));
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        operation,
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("postingId", posting));
        return (accounts.isEmpty()
                        ? authority(f).authorize(intent, false)
                        : authority(f).authorizeAccounts(intent, accounts, false))
                .block(Duration.ofSeconds(5));
    }

    static Mono<Long> rows(String table) {
        return db.sql("SELECT count(*) AS n FROM public." + table)
                .map((r, m) -> r.get("n", Long.class))
                .one();
    }

    @Test
    void accountAndBookScopesAreExactAndPoolReuseDoesNotRetainVisibility() throws Exception {
        var data = data();
        data();
        var f = data.fixture();
        var admitted = read(data, List.of(f.debit().toString()), "read");
        var actual =
                transactions
                        .execute(
                                admitted,
                                () -> Mono.zip(rows("gl_native_book"), rows("gl_native_account")))
                        .block(Duration.ofSeconds(5));
        assertThat(actual.getT1()).isEqualTo(1);
        assertThat(actual.getT2()).isEqualTo(1);
        assertThat(rows("gl_native_book").block()).isZero();
        assertThat(rows("gl_native_account").block()).isZero();
    }

    @Test
    void wholeReceiptHistoryRequiresEveryCurrentPostingAccountGrant() throws Exception {
        var data = data();
        var f = data.fixture();
        var one = read(data, List.of(f.debit().toString()), "history");
        var hidden =
                transactions
                        .execute(
                                one,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                        rows("gl_native_posting"),
                                                rows("gl_native_entry"),
                                                        rows("gl_native_journal_event")))
                        .block();
        assertThat(hidden.getT1()).isZero();
        assertThat(hidden.getT2()).isZero();
        assertThat(hidden.getT3()).isZero();
        assertThat(hidden.getT4()).isZero();
        var both = read(data, List.of(f.debit().toString(), f.credit().toString()), "history");
        var visible =
                transactions
                        .execute(
                                both,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                rows("gl_native_posting"),
                                                rows("gl_native_entry"),
                                                rows("gl_native_journal_event")))
                        .block();
        assertThat(visible.getT1()).isEqualTo(1);
        assertThat(visible.getT2()).isEqualTo(1);
        assertThat(visible.getT3()).isEqualTo(1);
        assertThat(visible.getT4()).isEqualTo(2);
    }

    @Test
    void explicitDiscoveryReturnsOnlyExactPostingAccountIdsAndNoFinancialRows() throws Exception {
        var data = data();
        var other = data();
        var f = data.fixture();
        var admitted = read(data, List.of(), "read.resources");
        var ids =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        db.sql(
                                                        "SELECT account_id FROM"
                                                            + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                .bind("tenant", f.scope().tenant())
                                                .bind("book", f.scope().book())
                                                .bind("posting", data.effect().posting())
                                                .map(
                                                        (r, m) ->
                                                                r.get("account_id", UUID.class)
                                                                        .toString())
                                                .all()
                                                .collectList())
                        .block();
        assertThat(ids).containsExactlyInAnyOrder(f.debit().toString(), f.credit().toString());
        var noDetails =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                rows("gl_native_posting"),
                                                rows("gl_native_line")))
                        .block();
        assertThat(noDetails.getT1()).isZero();
        assertThat(noDetails.getT2()).isZero();
        assertThat(noDetails.getT3()).isZero();
        var hidden =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        db.sql(
                                                        "SELECT account_id FROM"
                                                            + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                .bind("tenant", other.fixture().scope().tenant())
                                                .bind("book", other.fixture().scope().book())
                                                .bind("posting", other.effect().posting())
                                                .map((r, m) -> r.get("account_id", UUID.class))
                                                .all()
                                                .collectList())
                        .block();
        assertThat(hidden).isEmpty();
        var ordinary = read(data, List.of(f.debit().toString(), f.credit().toString()), "read");
        assertThat(
                        transactions
                                .execute(
                                        ordinary,
                                        () ->
                                                db.sql(
                                                                "SELECT account_id FROM"
                                                                    + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                        .bind("tenant", f.scope().tenant())
                                                        .bind("book", f.scope().book())
                                                        .bind("posting", data.effect().posting())
                                                        .map(
                                                                (r, m) ->
                                                                        r.get(
                                                                                "account_id",
                                                                                UUID.class))
                                                        .all()
                                                        .collectList())
                                .block())
                .isEmpty();
    }

    @Test
    void retainedReadDoesNotRequireBookReactivationOrAnOpenPeriod() throws Exception {
        var data = data();
        var f = data.fixture();
        try (var c = ownerConnection()) {
            execute(
                    c,
                    "UPDATE public.gl_native_book SET"
                            + " lifecycle='SUSPENDED',control_version=control_version+1 WHERE id='"
                            + f.scope().book()
                            + "';UPDATE public.gl_native_period SET"
                            + " lifecycle='FROZEN',control_version=control_version+1 WHERE id='"
                            + f.period()
                            + "'");
        }
        var admitted = read(data, List.of(f.debit().toString(), f.credit().toString()), "read");
        assertThat(transactions.execute(admitted, () -> rows("gl_native_posting")).block())
                .isEqualTo(1);
    }

    @Test
    void reconcileContextCannotWriteMoneyOrAdministrativeControls() throws Exception {
        var data = data();
        var f = data.fixture();
        var action = UUID.randomUUID().toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_POSTING_INTENT_V1",
                        List.of(
                                f.scope().tenant().toString(),
                                f.scope().book().toString(),
                                action));
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        "reconcile",
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("actionId", action));
        var admitted =
                authority(f)
                        .authorizeAccounts(
                                intent, List.of(f.debit().toString(), f.credit().toString()), true)
                        .block();
        var writes =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        Flux.concat(
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_book_sequence"
                                                                            + " SET last_sequence=last_sequence+1")
                                                                .fetch()
                                                                .rowsUpdated(),
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_account"
                                                                            + " SET lifecycle='SUSPENDED',control_version=control_version+1")
                                                                .fetch()
                                                                .rowsUpdated(),
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_book"
                                                                            + " SET lifecycle='SUSPENDED',control_version=control_version+1")
                                                                .fetch()
                                                                .rowsUpdated())
                                                .collectList())
                        .block();
        assertThat(writes).containsExactly(0L, 0L, 0L);
        assertThat(transactions.execute(admitted, () -> rows("gl_native_posting")).block())
                .isEqualTo(1);
    }

    @FunctionalInterface
    interface SqlFixture {
        void run(Connection connection) throws Exception;
    }

    static List<String> captured(SqlFixture fixture) throws Exception {
        var commands = new ArrayList<String>();
        var statement =
                (Statement)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Statement.class.getClassLoader(),
                                new Class[] {Statement.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("execute")) {
                                        commands.add((String) args[0]);
                                        return true;
                                    }
                                    if (method.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(
                                            "Private SQL capture supports execute/close only");
                                });
        var connection =
                (Connection)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class[] {Connection.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("createStatement"))
                                        return statement;
                                    if (method.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(
                                            "Private SQL capture supports createStatement/close"
                                                + " only");
                                });
        fixture.run(connection);
        return commands.stream()
                .map(
                        command ->
                                command.replace(
                                        "'https://synthetic.invalid','private-schema-fixture'",
                                        "'https://synthetic-scoped-gl.invalid','synthetic-scoped-gl-operator'"))
                .toList();
    }

    static Fixture emptyFixture() throws Exception {
        try (var c = ownerConnection()) {
            return fixture(c);
        }
    }

    static Effect scopedEffect(Fixture f, boolean due) throws Exception {
        try (var c = ownerConnection()) {
            var base = effect(c, due);
            var canonical =
                    GlPostingRules.canonical(
                            "GL_POSTING_INTENT_V1",
                            List.of(
                                    f.scope().tenant().toString(),
                                    f.scope().book().toString(),
                                    base.action().toString(),
                                    base.deadline() + "",
                                    f.period().toString(),
                                    f.mapping().toString(),
                                    f.debit().toString(),
                                    f.credit().toString(),
                                    "SYNTHETIC_PRIVATE_POLICY_FIXTURE"));
            return new Effect(
                    base.action(),
                    base.posting(),
                    base.receipt(),
                    base.sourceKey(),
                    canonical,
                    base.deadline());
        }
    }

    static GlAccountingAuthority.Admission write(Fixture f, Effect effect, String operation) {
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        operation,
                        GlPostingRules.digest(effect.canonical()),
                        effect.canonical(),
                        Map.of("actionId", effect.action().toString()));
        return authority(f)
                .authorizeAccounts(
                        intent, List.of(f.debit().toString(), f.credit().toString()), true)
                .block(Duration.ofSeconds(5));
    }

    static Mono<Long> executeCommands(
            List<String> commands, GlAccountingAuthority.Admission admitted) {
        return Flux.fromIterable(commands)
                .concatMap(
                        command ->
                                db.sql(
                                                command.replace(
                                                        "floor(extract(epoch FROM"
                                                            + " clock_timestamp())*1000)::bigint+60000",
                                                        admitted.validUntilEpochMillis()
                                                                .toString()))
                                        .fetch()
                                        .rowsUpdated())
                .reduce(0L, Long::sum);
    }

    @Test
    void financialWriteRequiresTheExactAuthenticatedActorProvenance() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, true);
        var admitted = write(f, effect, "reconcile");
        var commands =
                captured(
                        c -> {
                            action(c, f, effect, "ABSENT_CLOSED");
                            event(c, effect, "AUDIT");
                            event(c, effect, "OUTBOX");
                        });
        for (var change :
                List.of(
                        Map.entry("'synthetic-scoped-gl-operator'", "'different-unapproved-actor'"),
                        Map.entry(
                                "'https://synthetic-scoped-gl.invalid'",
                                "'https://different-unapproved-issuer.invalid'"))) {
            var tampered =
                    commands.stream()
                            .map(command -> command.replace(change.getKey(), change.getValue()))
                            .toList();
            assertThatThrownBy(
                            () ->
                                    transactions
                                            .execute(
                                                    admitted,
                                                    () -> executeCommands(tampered, admitted))
                                            .block(Duration.ofSeconds(5)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_journal_action", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isZero();
        }
    }

    @Test
    void currentPostContextCommitsCompleteJournalAndMissingOutboxRollsEverythingBack()
            throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        var admitted = write(f, effect, "post");
        var commands = captured(c -> complete(c, f, effect, 1, null, "9007199254740993"));
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(commands, admitted)
                                                        .then(rows("gl_native_posting")))
                                .block(Duration.ofSeconds(5)))
                .isEqualTo(1);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_line", f.scope().book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
        var rollbackFixture = emptyFixture();
        var rollbackEffect = scopedEffect(rollbackFixture, false);
        var rollback = write(rollbackFixture, rollbackEffect, "post");
        var incomplete =
                captured(c -> complete(c, rollbackFixture, rollbackEffect, 1, null, "12500"))
                        .stream()
                        .filter(command -> !command.contains("'OUTBOX'"))
                        .toList();
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                rollback,
                                                () -> executeCommands(incomplete, rollback))
                                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event"))
                assertThat(count(c, table, rollbackFixture.scope().book())).isZero();
            try (var st = c.createStatement();
                    var rows =
                            st.executeQuery(
                                    "SELECT last_sequence FROM gl_native_book_sequence WHERE"
                                        + " book_id='"
                                            + rollbackFixture.scope().book()
                                            + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    void reconcileContextCanCommitOnlyAnAuditedNoEffectClosureAndCannotExtendIt() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, true);
        var admitted = write(f, effect, "reconcile");
        var closure =
                captured(
                        c -> {
                            action(c, f, effect, "ABSENT_CLOSED");
                            event(c, effect, "AUDIT");
                            event(c, effect, "OUTBOX");
                        });
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(closure, admitted)
                                                        .then(rows("gl_native_journal_action")))
                                .block(Duration.ofSeconds(5)))
                .isEqualTo(1);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_posting", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_line", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
        var money = captured(c -> posting(c, f, effect, 1, null));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(admitted, () -> executeCommands(money, admitted))
                                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_posting", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }
}
