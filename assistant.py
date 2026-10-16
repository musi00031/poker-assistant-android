from poker import parse_card, equity, evaluate, NAMES
from poker_math import pot_odds, call_decision


def read_cards(prompt, allowed):
    while True:
        raw = input(prompt).strip()

        if allowed == 0 and not raw:
            return []

        try:
            cards = [parse_card(x) for x in raw.split()]

            if len(cards) != allowed:
                print(f"Нужно указать ровно {allowed} карт.")
                continue

            if len(set(cards)) != len(cards):
                print("Одинаковые карты указывать нельзя.")
                continue

            return cards

        except ValueError as exc:
            print(f"Ошибка: {exc}")


def read_number(prompt, minimum=0):
    while True:
        try:
            value = float(input(prompt).strip().replace(",", "."))

            if value < minimum:
                print(f"Значение должно быть не меньше {minimum}.")
                continue

            return value

        except ValueError:
            print("Введи число.")


def main():
    print("\n" + "=" * 36)
    print("       POKER ASSISTANT 2.0")
    print("=" * 36)
    print("Карты: As Kh Qd Jc Ts")
    print("Масти: s — пики, h — червы,")
    print("       d — бубны, c — трефы\n")

    hero = read_cards("Твои две карты: ", 2)

    while True:
        try:
            count = int(input("Общих карт (0, 3, 4 или 5): "))

            if count in (0, 3, 4, 5):
                break

        except ValueError:
            pass

        print("Допустимые значения: 0, 3, 4, 5.")

    board = read_cards("Общие карты через пробел: ", count)

    if len(set(hero + board)) != len(hero) + len(board):
        print("Ошибка: карты повторяются.")
        return

    print("\nРассчитываем эквити...")
    result = equity(hero, board, simulations=10000)

    print(f"\nЭквити против случайной руки: {result:.2f}%")

    if len(board) >= 3:
        hand = evaluate(hero + board)
        print(f"Текущая комбинация: {NAMES[hand[0]]}")

    print("\n--- РАСЧЁТ БАНКА ---")

    pot = read_number("Банк до ставки соперника: ")
    bet = read_number("Ставка соперника: ")
    call = read_number("Сколько нужно доплатить для колла: ")

    odds = pot_odds(pot, bet, call)

    print("\n--- РЕЗУЛЬТАТ ---")
    print(f"Банк до ставки: {pot:.2f}")
    print(f"Ставка соперника: {bet:.2f}")
    print(f"Твой колл: {call:.2f}")
    print(f"Банк после колла: {odds['final_pot']:.2f}")
    print(
        "Необходимое эквити: "
        f"{odds['required_equity']:.2f}%"
    )

    decision = call_decision(
        result,
        odds["required_equity"]
    )

    print(f"Твоё эквити: {decision['equity']:.2f}%")
    print(f"Разница: {decision['margin']:+.2f}%")
    print(f"Оценка: {decision['verdict']}")

    print("\nВажно:")
    print("Эквити рассчитано против случайной руки.")
    print("Реальный диапазон соперника может отличаться.")
    print("Расчёт не учитывает рейк и будущие ставки.")


if __name__ == "__main__":
    main()
