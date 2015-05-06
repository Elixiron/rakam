package org.rakam.plugin.user.mailbox;

import com.google.auto.service.AutoService;
import com.google.inject.Binder;
import org.rakam.analysis.postgresql.PostgresqlConfig;
import org.rakam.plugin.ConditionalModule;
import org.rakam.plugin.RakamModule;

import static io.airlift.configuration.ConfigurationModule.bindConfig;

/**
 * Created by buremba <Burak Emre Kabakcı> on 30/03/15 15:55.
 */
@AutoService(RakamModule.class)
@ConditionalModule(config="plugin.user.mailbox.adapter", value="postgresql")
public class PostgresqlUserMailboxModule extends RakamModule {

    @Override
    protected void setup(Binder binder) {
        bindConfig(binder).to(PostgresqlConfig.class);
        binder.bind(UserMailboxStorage.class).to(PostgresqlUserMailboxStorage.class);
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }
}
