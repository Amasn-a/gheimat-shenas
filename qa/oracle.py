"""TEST-ONLY oracle: reads the hidden comparison price of the open question straight from the database so a QA script can
choose a correct or a wrong answer on purpose. Never available to the player app or its API."""
import psycopg2, os
DSN = os.environ.get("PE_QA_DSN", "dbname=price_expert user=pe password=pe host=127.0.0.1")
def open_question(user_id):
    with psycopg2.connect(DSN) as c, c.cursor() as cur:
        cur.execute("""SELECT q.reference_price, q.comparison_price FROM pe_question q JOIN pe_run r ON r.run_id = q.run_id
                       WHERE r.user_id = %s AND q.state = 'OPEN' ORDER BY q.released_at DESC LIMIT 1""", (user_id,))
        return cur.fetchone()
def choice(user_id, correct=True):
    row = open_question(user_id)
    if not row: return None
    ref, cmp = row
    right = "higher" if cmp > ref else "lower"
    return right if correct else ("lower" if right == "higher" else "higher")
