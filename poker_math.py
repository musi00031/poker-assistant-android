def pot_odds(pot, bet, call_amount=None):
    """
    pot — банк до ставки соперника.
    bet — ставка соперника.
    call_amount — сумма, которую нужно доплатить.
    Если не указана, считаем, что колл равен bet.
    """
    pot = float(pot)
    bet = float(bet)
    call_amount = bet if call_amount is None else float(call_amount)

    if pot < 0 or bet < 0 or call_amount < 0:
        raise ValueError("Суммы не могут быть отрицательными")

    final_pot = pot + bet + call_amount

    if final_pot == 0:
        return {
            "pot_before": pot,
            "opponent_bet": bet,
            "call_amount": call_amount,
            "final_pot": final_pot,
            "required_equity": 0.0,
            "pot_odds": None,
        }

    required_equity = call_amount / final_pot * 100

    return {
        "pot_before": pot,
        "opponent_bet": bet,
        "call_amount": call_amount,
        "final_pot": final_pot,
        "required_equity": required_equity,
        "pot_odds": (pot + bet) / call_amount
        if call_amount > 0 else None,
    }


def call_decision(equity_percent, required_equity_percent):
    """
    Сравнивает эквити с порогом безубыточного колла.
    Не учитывает рейк, будущие ставки и позиционные факторы.
    """
    equity = float(equity_percent)
    required = float(required_equity_percent)

    if not 0 <= equity <= 100:
        raise ValueError("Эквити должно быть от 0 до 100")

    if not 0 <= required <= 100:
        raise ValueError("Порог эквити должен быть от 0 до 100")

    difference = equity - required

    if difference > 1:
        verdict = "Колл математически перспективен"
    elif difference < -1:
        verdict = "Колл математически невыгоден"
    else:
        verdict = "Пограничное решение"

    return {
        "equity": equity,
        "required_equity": required,
        "margin": difference,
        "verdict": verdict,
    }


if __name__ == "__main__":
    result = pot_odds(pot=1000, bet=500, call_amount=500)

    print("=== POKER ASSISTANT: POT ODDS ===")
    print(f"Банк до ставки: {result['pot_before']:.2f}")
    print(f"Ставка соперника: {result['opponent_bet']:.2f}")
    print(f"Колл: {result['call_amount']:.2f}")
    print(f"Банк после колла: {result['final_pot']:.2f}")
    print(f"Необходимое эквити: {result['required_equity']:.2f}%")
