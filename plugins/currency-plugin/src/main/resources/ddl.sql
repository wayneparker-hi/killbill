/*
 * Copyright 2020-2023 Equinix, Inc
 * Copyright 2014-2023 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*! SET default_storage_engine=INNODB */;

create table currency_updates (
  record_id serial
, base_currency char(3) not null
, conversion_date datetime not null
, created_at datetime not null
, updated_at datetime not null
, kb_tenant_id char(36) -- not null
, primary key(record_id)
) /*! CHARACTER SET utf8 COLLATE utf8_bin */;
create index currency_updates_base_currency on currency_updates(base_currency);

create table currency_rates (
  record_id serial
, target_currency char(3) not null
, rate numeric(15,9) not null
, currency_update_record_id int not null
, created_at datetime not null
, updated_at datetime not null
, kb_tenant_id char(36) -- not null
, primary key(record_id)
) /*! CHARACTER SET utf8 COLLATE utf8_bin */;
create index currency_rates_currency_update_record_id on currency_rates(currency_update_record_id);
