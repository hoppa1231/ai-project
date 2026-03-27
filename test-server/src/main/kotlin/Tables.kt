package com.example

// Класс таблиц IntIdTable автоматически добавляет столбец с автоинкрементными
// целочисленными значениями id в качестве первичного ключа отношения.
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
// Зависимости для работы с параметрами создания записей в таблицах
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table.*

// Зависимости для работы с DAO и для создания класса, связанного с отношением
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

// Для кастомных типов данных
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Column






const val MAX_VARCHAR_LENGTH = 100






// Конструктор и фабрика для ограниченного в значениях строкового типа
class RestrictedStringColumnType<T : Any>(
    private val allowed: Set<String>,
    private val typeName: String,
    private val factory: (String) -> T,      // Создаёт T из String
    private val valueExtractor: (T) -> String // Извлекает String из T
) : ColumnType<T>() {
    
    override fun sqlType(): String = "VARCHAR(50)"
    
    override fun valueFromDB(value: Any): T {
        val str = when (value) {
            is String -> value
            is ByteArray -> String(value, Charsets.UTF_8)
            is Char -> value.toString()
            else -> value.toString()
        }
        require(str in allowed) {
            "Invalid $typeName: '$str'. Allowed: $allowed"
        }
        return factory(str)
    }
    
    override fun notNullValueToDB(value: T): Any = 
        valueExtractor(value)
    
    override fun valueToString(value: T?): String =
        value?.let { "'${valueExtractor(it)}'" } ?: "NULL"
}

// Кастомный тип status_user для Users_tables
@JvmInline
value class StatusUser private constructor(val value: String) {
    companion object {
        private val ALLOWED = setOf("ONLINE", "OFFLINE", "FROZED")
        private const val NAME = "StatusUser"
        
        fun of(value: String): StatusUser {
            require(value in ALLOWED) { "Invalid $NAME: '$value'. Allowed: $ALLOWED" }
            return StatusUser(value)
        }
        
        fun safeOf(value: String): StatusUser? = 
            runCatching { of(value) }.getOrNull()
        
        fun columnType() = RestrictedStringColumnType(
            allowed = ALLOWED,
            typeName = NAME,
            factory = ::of,
            valueExtractor = { it.value }
        )
    }
}
// Кастомный класс status_node для VPN_nodes_tables
@JvmInline
value class StatusNode private constructor(val value: String) {
    companion object {
        private val ALLOWED = setOf("ACTIVE", "INACTIVE", "PROBLEM", "STOP")
        private const val NAME = "StatusNode"
        
        fun of(value: String): StatusNode {
            require(value in ALLOWED) { "Invalid $NAME: '$value'. Allowed: $ALLOWED" }
            return StatusNode(value)
        }
        
        fun safeOf(value: String): StatusNode? = 
            runCatching { of(value) }.getOrNull()
        
        fun columnType() = RestrictedStringColumnType(
            allowed = ALLOWED,
            typeName = NAME,
            factory = ::of,
            valueExtractor = { it.value }
        )
    }
}






/*************************************************************
**                     ОСНОВНЫЕ ТАБЛИЦЫ:                    **
**  users                                                   **
**  vpn_nodes                                               **
**  node_clients                                            **
**  policies                                                **
**  issued_configs                                          **
**  traffic                                                 **
*************************************************************/

// Таблица пользователей
object Users_tables : IntIdTable("users") {
    val login       = varchar("login", MAX_VARCHAR_LENGTH)          // Логин
    val password    = varchar("password", MAX_VARCHAR_LENGTH)       // Кэш-пароль
    // Конфигурации
    val configs     = optReference("configs", Issued_configs_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    // Состояние
    val status: Column<StatusUser> = Column(this, "status", StatusUser.columnType())
    // Текущий сервер
    val server      = optReference("server", VPN_nodes_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    // Маршрутизация
    val policy      = optReference("policy", Policies_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
}

// Доступные "VPN-сети"
object VPN_nodes_tables : IntIdTable("vpn_nodes") {
    val region  = varchar("region", MAX_VARCHAR_LENGTH)     // Регион сервера
    val address = varchar("address", 15)                    // IP-адрес сервера
    // Состояние сервера
    val status: Column<StatusNode> = Column(this, "status", StatusNode.columnType())
    val online  = integer("online").default(0)              // Количество клиентов
}

// Информация о клиенте на VPN-сервере
object Node_clients_tables : IntIdTable("node_clients") {
    // ID сервера
    val server  = optReference("server", VPN_nodes_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val client  = varchar("client", MAX_VARCHAR_LENGTH)     // ID клиента на сервере
    // Трафик клиента
    val traffic = optReference("traffic", Traffic_use_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
}

// Правила маршрутизации
object Policies_tables : IntIdTable("policies") {
    val filename    = varchar("filename", MAX_VARCHAR_LENGTH)   // Имя файла
    // ID пользователя
    val user        = optReference("user", Users_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
}

// Хранит конфиги и версии конфигов (версионирование конфигов)
object Issued_configs_tables : IntIdTable("issued_configs") {
    val version = integer("version")                    // Версия конфига
    val name    = varchar("name", MAX_VARCHAR_LENGTH)   // Имя конфига
    // Сервер с конфигом
    val server  = optReference("server", VPN_nodes_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
}

// Хранит информацию об использованном трафике
object Traffic_use_tables : IntIdTable("traffic") {
    // ID пользователя
    val user    = optReference("user", Users_tables.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val limit   = float("limit").nullable()
    val current = double("current_limit").default(0.0)    
}






/****************************/
/* Создание классов для DAO */
/****************************/

// Связываем таблицу с соответствующим классом сущностей.
// Наследуем IntEntity, то есть сущность с целочисленным первичным ключом.
// EntityID - первичный ключ поля БД, к которой относится данная сущность
class Users_table(id: EntityID<Int>) : IntEntity(id) {
    // Наследуем класс IntEntityClass, который связывает класс сущности
    // с отношением Users_tables
    companion object : IntEntityClass<Users_table>(Users_tables)
    // Каждое поле к каждому свойству класса
    var login by Users_tables.login
    var password by Users_tables.password
    var configs by Users_tables.configs
    var status by Users_tables.status
    var server by Users_tables.server
    var policy by Users_tables.policy
}

class VPN_nodes_table(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<VPN_nodes_table>(VPN_nodes_tables)
    var region by VPN_nodes_tables.region
    var address by VPN_nodes_tables.address
    var status by VPN_nodes_tables.status
    var online by VPN_nodes_tables.online
}

class Node_clients_table(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Node_clients_table>(Node_clients_tables)
    var server by Node_clients_tables.server
    var client by Node_clients_tables.client
    var traffic by Node_clients_tables.traffic
}

class Policies_table(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Policies_table>(Policies_tables)
    var filename by Policies_tables.filename
    var user by Policies_tables.user
}

class Issued_configs_table(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Issued_configs_table>(Issued_configs_tables)
    var version by Issued_configs_tables.version
    var name by Issued_configs_tables.name
    var server by Issued_configs_tables.server
}

class Traffic_use_table(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Traffic_use_table>(Traffic_use_tables)
    var user by Traffic_use_tables.user
    var limit by Traffic_use_tables.limit
    var current by Traffic_use_tables.current
}

//        "====================================================================================\n" +
//        "=====00=======0000000==0000000000==00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00======00==00==============00===00==00=======00=========\n" +
//        "=====00=======0000000=====и=00======00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00================00========00===00==00=======00=========\n" +
//        "=====0000000==0000000======00==========00000000========00===00==0000000==0000000====\n" +
//        "====================================================================================\n" +
//        "==00=======00000000==00===00==0000000========00=======0000000==00000000===000===00==\n" +
//        "==00=======00====00==00===00==00=============00=======00===00=====00======0000==00==\n" +
//        "==00=======00====00==00===00==0000000========00=======0000000=====00======00=00=00==\n" +
//        "==00=======00====00===00=00===00=============00=======00===00=====00======00==0000==\n" +
//        "==0000000==00000000====000====0000000========0000000==00===00==00000000===00===000==\n" +
//        "===================================================================================="