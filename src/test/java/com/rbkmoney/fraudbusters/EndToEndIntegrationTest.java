package com.rbkmoney.fraudbusters;

import com.rbkmoney.damsel.domain.RiskScore;
import com.rbkmoney.damsel.fraudbusters.*;
import com.rbkmoney.damsel.geo_ip.LocationInfo;
import com.rbkmoney.damsel.proxy_inspector.Context;
import com.rbkmoney.damsel.proxy_inspector.InspectorProxySrv;
import com.rbkmoney.fraudbusters.util.BeanUtil;
import com.rbkmoney.fraudbusters.util.FileUtil;
import com.rbkmoney.fraudbusters.util.ReferenceKeyGenerator;
import com.rbkmoney.woody.thrift.impl.http.THClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.thrift.TException;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.ClickHouseContainer;
import ru.yandex.clickhouse.ClickHouseDataSource;
import ru.yandex.clickhouse.settings.ClickHouseProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.any;

@Slf4j
@ContextConfiguration(initializers = EndToEndIntegrationTest.Initializer.class)
public class EndToEndIntegrationTest extends KafkaAbstractTest {

    private static final String TEMPLATE =
            "rule: count(\"email\", 10) > 1  AND count(\"email\", 10) < 3 " +
                    "AND sum(\"email\", 10) >= 9000 " +
                    "AND count(\"card_token\", 10) > 1 " +
                    "AND in(countryBy(\"country_bank\"), \"RUS\") \n" +
            " -> decline;";

    private static final String TEMPLATE_CONCRETE =
            "rule:  sum(\"email\", 10) >= 20000  -> decline;";

    private static final String TEMPLATE_CONCRETE_SHOP =
            "rule:  sum(\"email\", 10) >= 9000  -> accept;";

    private static final int COUNTRY_GEO_ID = 12345;
    public static final String P_ID = "test";

    private InspectorProxySrv.Iface client;

    @LocalServerPort
    int serverPort;

    @Value("${kafka.global.stream.topic}")
    public String GLOBAL_TOPIC;

    private static String SERVICE_URL = "http://localhost:%s/v1/fraud_inspector";

    @ClassRule
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer();

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
            log.info("clickhouse.db.url={}", clickHouseContainer.getJdbcUrl());
            TestPropertyValues
                    .of("clickhouse.db.url=" + clickHouseContainer.getJdbcUrl())
                    .applyTo(configurableApplicationContext.getEnvironment());
            LocationInfo info = new LocationInfo();
            info.setCountryGeoId(COUNTRY_GEO_ID);
        }
    }

    private Connection getSystemConn() throws SQLException {
        ClickHouseProperties properties = new ClickHouseProperties();
        ClickHouseDataSource dataSource = new ClickHouseDataSource(clickHouseContainer.getJdbcUrl(), properties);
        return dataSource.getConnection();
    }

    @Before
    public void init() throws ExecutionException, InterruptedException, SQLException, TException {
        Connection connection = getSystemConn();
        String sql = FileUtil.getFile("sql/db_init.sql");
        String[] split = sql.split(";");
        for (String exec : split) {
            connection.createStatement().execute(exec);
        }
        connection.close();

        String globalRef = UUID.randomUUID().toString();
        produceTemplate(globalRef, TEMPLATE);
        produceReference(true, null, null, globalRef);

        String partyTemplate = UUID.randomUUID().toString();
        produceTemplate(partyTemplate, TEMPLATE_CONCRETE);
        produceReference(false, P_ID, null, partyTemplate);

        String shopRef = UUID.randomUUID().toString();
        produceTemplate(shopRef, TEMPLATE_CONCRETE_SHOP);
        produceReference(false, P_ID, BeanUtil.ID_VALUE_SHOP, shopRef);

        Thread.sleep(3000L);

        Mockito.when(geoIpServiceSrv.getLocationIsoCode(any())).thenReturn("RUS");
    }

    private void produceTemplate(String localId, String templateString) throws InterruptedException, ExecutionException {
        Producer<String, Command> producer;
        ProducerRecord<String, Command> producerRecord;
        producer = createProducer();
        Command command = crateCommandTemplate(localId, templateString);
        producerRecord = new ProducerRecord<>(templateTopic, localId, command);
        producer.send(producerRecord).get();
        producer.close();
    }

    private void produceReference(boolean isGlobal, String party, String shopId, String idTemplate) throws InterruptedException, ExecutionException {
        Producer<String, Command> producer = createProducer();
        ProducerRecord<String, Command> producerRecord;
        Command command = new Command();
        command.setCommandType(CommandType.CREATE);
        TemplateReference value = new TemplateReference();
        value.setTemplateId(idTemplate);
        value.setPartyId(party);
        value.setShopId(shopId);
        value.setIsGlobal(isGlobal);
        command.setCommandBody(CommandBody.reference(value));
        String key = ReferenceKeyGenerator.generateTemplateKey(value);
        producerRecord = new ProducerRecord<>(referenceTopic, key, command);
        producer.send(producerRecord).get();
        producer.close();
    }

    @NotNull
    private Command crateCommandTemplate(String localId, String templateString) {
        Command command = new Command();
        Template template = new Template();
        template.setId(localId);
        template.setTemplate(templateString.getBytes());
        command.setCommandBody(CommandBody.template(template));
        command.setCommandType(com.rbkmoney.damsel.fraudbusters.CommandType.CREATE);
        return command;
    }

    @Test
    public void test() throws URISyntaxException, TException, InterruptedException {
        THClientBuilder clientBuilder = new THClientBuilder()
                .withAddress(new URI(String.format(SERVICE_URL, serverPort)))
                .withNetworkTimeout(300000);
        client = clientBuilder.build(InspectorProxySrv.Iface.class);

        Context context = BeanUtil.createContext();
        RiskScore riskScore = client.inspectPayment(context);
        Assert.assertEquals(RiskScore.high, riskScore);

        context = BeanUtil.createContext();
        riskScore = client.inspectPayment(context);
        Assert.assertEquals(RiskScore.fatal, riskScore);

        context = BeanUtil.createContext(P_ID);
        riskScore = client.inspectPayment(context);
        Assert.assertEquals(RiskScore.low, riskScore);
    }

}