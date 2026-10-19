package com.classlib.bookcorner.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 全部业务表。7 张：member / book / copy / loan / op_log / setting（6 张，+ 预留）。
 *
 * 两条关键约束：
 * 1) ux_loan_one_active —— 一册书同时只能有一条 active 借阅，由数据库保证
 * 2) status 字段的 CHECK —— 非法状态写不进来
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE member (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                pinyin      TEXT    NOT NULL,
                initials    TEXT    NOT NULL,
                role        TEXT    NOT NULL DEFAULT 'student'
                                    CHECK (role IN ('student','librarian','teacher')),
                status      TEXT    NOT NULL DEFAULT 'active'
                                    CHECK (status IN ('active','left')),
                sort_no     INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_member_initials ON member(initials)")
        db.execSQL("CREATE INDEX idx_member_pinyin ON member(pinyin)")

        db.execSQL(
            """
            CREATE TABLE book (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                isbn        TEXT,
                title       TEXT    NOT NULL,
                author      TEXT,
                publisher   TEXT,
                category    TEXT    NOT NULL,
                source      TEXT    NOT NULL DEFAULT 'scan'
                                    CHECK (source IN ('scan','voice','manual')),
                created_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_book_title ON book(title)")
        db.execSQL("CREATE INDEX idx_book_isbn ON book(isbn)")

        db.execSQL(
            """
            CREATE TABLE copy (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id        INTEGER NOT NULL REFERENCES book(id),
                barcode        TEXT    NOT NULL UNIQUE,
                status         TEXT    NOT NULL DEFAULT 'in'
                                       CHECK (status IN ('in','out','lost','removed')),
                owner_id       INTEGER REFERENCES member(id),
                shelf          TEXT,
                in_at          INTEGER NOT NULL,
                removed_reason TEXT,
                version        INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_copy_book ON copy(book_id)")
        db.execSQL("CREATE INDEX idx_copy_status ON copy(status)")
        db.execSQL("CREATE INDEX idx_copy_owner ON copy(owner_id)")

        db.execSQL(
            """
            CREATE TABLE loan (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                copy_id       INTEGER NOT NULL REFERENCES copy(id),
                member_id     INTEGER NOT NULL REFERENCES member(id),
                borrowed_at   INTEGER NOT NULL,
                due_at        INTEGER NOT NULL,
                returned_at   INTEGER,
                status        TEXT    NOT NULL DEFAULT 'active'
                                      CHECK (status IN ('active','returned','lost')),
                operator_id   INTEGER REFERENCES member(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_loan_copy ON loan(copy_id, status)")
        db.execSQL("CREATE INDEX idx_loan_member ON loan(member_id, status)")
        db.execSQL("CREATE INDEX idx_loan_due ON loan(due_at)")

        // 核心约束：一册书同时只能有一条 active 借阅
        db.execSQL(
            "CREATE UNIQUE INDEX ux_loan_one_active ON loan(copy_id) WHERE status = 'active'"
        )

        db.execSQL(
            """
            CREATE TABLE op_log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                action      TEXT    NOT NULL,
                target_type TEXT    NOT NULL,
                target_id   INTEGER NOT NULL,
                from_status TEXT,
                to_status   TEXT,
                operator_id INTEGER,
                note        TEXT,
                created_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_op_target ON op_log(target_type, target_id)")

        db.execSQL(
            """
            CREATE TABLE setting (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )

        // 默认配置
        db.execSQL("INSERT INTO setting(key,value) VALUES('loan_days','14')")
        db.execSQL("INSERT INTO setting(key,value) VALUES('class_code','03')")
        db.execSQL("INSERT INTO setting(key,value) VALUES('camera_facing','back')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 原则：只做增量，绝不 DROP。后续加字段写在这里。
        // if (oldVersion < 2) db.execSQL("ALTER TABLE copy ADD COLUMN xxx TEXT")
    }

    companion object {
        const val DB_NAME = "lib.db"
        const val DB_VERSION = 1
    }
}
